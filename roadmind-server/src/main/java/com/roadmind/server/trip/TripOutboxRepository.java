package com.roadmind.server.trip;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class TripOutboxRepository {

    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;
    private final ObjectMapper objectMapper;
    private volatile TransactionTemplate transactionTemplate;
    private final RowMapper<TripOutboxEvent> mapper = this::map;

    public TripOutboxRepository(
            ObjectProvider<JdbcTemplate> jdbcTemplateProvider,
            ObjectMapper objectMapper) {
        this.jdbcTemplateProvider = jdbcTemplateProvider;
        this.objectMapper = objectMapper;
    }

    public boolean isAvailable() {
        JdbcTemplate jdbc = jdbcTemplateProvider.getIfAvailable();
        return jdbc != null && jdbc.getDataSource() != null;
    }

    public List<TripOutboxEvent> claimDue(
            String workerId,
            Instant now,
            Instant leaseUntil,
            int limit) {
        return transactionTemplate().execute(status -> {
            JdbcTemplate jdbc = jdbc();
            List<Long> ids = jdbc.queryForList("""
                    SELECT id FROM domain_event_outbox
                    WHERE available_at <= ?
                      AND (
                          status = 'PENDING'
                          OR (
                              status = 'CLAIMED'
                              AND locked_until IS NOT NULL
                              AND locked_until <= ?
                          )
                      )
                    ORDER BY id
                    LIMIT %d
                    FOR UPDATE SKIP LOCKED
                    """.formatted(safeLimit(limit)), Long.class,
                    timestamp(now), timestamp(now));
            for (Long id : ids) {
                jdbc.update("""
                        UPDATE domain_event_outbox
                        SET status = 'CLAIMED', locked_by = ?, locked_until = ?,
                            attempt_count = attempt_count + 1, updated_at = ?
                        WHERE id = ?
                        """, workerId, timestamp(leaseUntil), timestamp(now), id);
            }
            return ids.stream()
                    .map(id -> findById(id).orElseThrow())
                    .toList();
        });
    }

    public boolean markDelivered(long id, String workerId, Instant now) {
        return jdbc().update("""
                UPDATE domain_event_outbox
                SET status = 'DELIVERED', locked_by = NULL, locked_until = NULL,
                    delivered_at = ?, updated_at = ?, last_error_code = NULL
                WHERE id = ? AND status = 'CLAIMED' AND locked_by = ?
                """, timestamp(now), timestamp(now), id, workerId) > 0;
    }

    public boolean markFailed(long id, String workerId, String errorCode, Instant now) {
        return jdbc().update("""
                UPDATE domain_event_outbox
                SET status = CASE WHEN attempt_count >= 5 THEN 'FAILED' ELSE 'PENDING' END,
                    locked_by = NULL, locked_until = NULL, last_error_code = ?,
                    available_at = ?, updated_at = ?
                WHERE id = ? AND status = 'CLAIMED' AND locked_by = ?
                """, errorCode, timestamp(now.plusSeconds(5)), timestamp(now), id, workerId) > 0;
    }

    public Optional<TripOutboxEvent> findById(long id) {
        return jdbc().query("SELECT " + columns() + " FROM domain_event_outbox WHERE id = ?", mapper, id)
                .stream().findFirst();
    }

    private TripOutboxEvent map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new TripOutboxEvent(
                resultSet.getLong("id"),
                resultSet.getString("aggregate_type"),
                resultSet.getString("aggregate_id"),
                resultSet.getString("event_id"),
                resultSet.getLong("sequence"),
                resultSet.getString("event_type"),
                resultSet.getString("trace_id"),
                resultSet.getTimestamp("created_at").toInstant(),
                readJson(resultSet.getString("payload_json")),
                resultSet.getString("status"),
                resultSet.getInt("attempt_count"));
    }

    private Map<String, Object> readJson(String value) throws SQLException {
        try {
            JsonNode node = objectMapper.readTree(value);
            return objectMapper.convertValue(node, Map.class);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Outbox JSON 无法解析", exception);
        }
    }

    private String columns() {
        return "id, aggregate_type, aggregate_id, event_id, sequence, event_type, trace_id, "
                + "payload_json, status, attempt_count, created_at";
    }

    private Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private int safeLimit(int limit) {
        return Math.max(1, Math.min(limit, 100));
    }

    private JdbcTemplate jdbc() {
        JdbcTemplate jdbc = jdbcTemplateProvider.getIfAvailable();
        if (jdbc == null) throw new IllegalStateException("Trip Outbox 数据库连接不可用");
        return jdbc;
    }

    private TransactionTemplate transactionTemplate() {
        TransactionTemplate current = transactionTemplate;
        if (current != null) return current;
        synchronized (this) {
            if (transactionTemplate == null) {
                DataSource dataSource = jdbc().getDataSource();
                if (dataSource == null) throw new IllegalStateException("Trip Outbox 需要 DataSource");
                transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            }
            return transactionTemplate;
        }
    }
}
