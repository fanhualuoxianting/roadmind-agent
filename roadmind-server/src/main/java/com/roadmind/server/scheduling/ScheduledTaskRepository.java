package com.roadmind.server.scheduling;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class ScheduledTaskRepository {

    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;
    private final ObjectMapper objectMapper;
    private volatile TransactionTemplate transactionTemplate;
    private final RowMapper<ScheduledTaskSnapshot> rowMapper = this::map;

    public ScheduledTaskRepository(
            ObjectProvider<JdbcTemplate> jdbcTemplateProvider,
            ObjectMapper objectMapper) {
        this.jdbcTemplateProvider = jdbcTemplateProvider;
        this.objectMapper = objectMapper;
    }

    public boolean isAvailable() {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        return jdbcTemplate != null && jdbcTemplate.getDataSource() != null;
    }

    public long requireUserId(String username) {
        try {
            return jdbc().queryForObject(
                    "SELECT id FROM `user` WHERE username = ? AND status = 'ACTIVE'",
                    Long.class,
                    username);
        } catch (EmptyResultDataAccessException exception) {
            throw new IllegalStateException("当前用户未在数据库中注册: " + username, exception);
        }
    }

    public Optional<ScheduledTaskSnapshot> findById(long id) {
        return query("SELECT " + columns() + " FROM scheduled_task WHERE id = ?", id)
                .stream()
                .findFirst();
    }

    public Optional<ScheduledTaskSnapshot> findByIdForUser(long id, long userId) {
        return query("SELECT " + columns() + " FROM scheduled_task WHERE id = ? AND user_id = ?", id, userId)
                .stream()
                .findFirst();
    }

    public Optional<ScheduledTaskSnapshot> findByIdempotencyKey(String idempotencyKey) {
        return query(
                        "SELECT " + columns() + " FROM scheduled_task WHERE idempotency_key = ?",
                        idempotencyKey)
                .stream()
                .findFirst();
    }

    public List<ScheduledTaskSnapshot> findForUser(long userId, String status, int limit) {
        String sql = "SELECT " + columns() + " FROM scheduled_task WHERE user_id = ?";
        if (status == null || status.isBlank()) {
            sql += " ORDER BY id DESC LIMIT " + safeLimit(limit);
            return query(sql, userId);
        }
        sql += " AND status = ? ORDER BY id DESC LIMIT " + safeLimit(limit);
        return query(sql, userId, status);
    }

    public ScheduledTaskSnapshot insert(
            long userId,
            String taskType,
            String payloadJson,
            String payloadHash,
            String idempotencyKey,
            Instant executeAt,
            String timezone,
            Instant now) {
        return insert(userId, taskType, payloadJson, payloadHash, idempotencyKey,
                executeAt, timezone, now, null, null, null, null, null);
    }

    public ScheduledTaskSnapshot insert(
            long userId,
            String taskType,
            String payloadJson,
            String payloadHash,
            String idempotencyKey,
            Instant executeAt,
            String timezone,
            Instant now,
            String authorizationWorkflowId,
            String authorizationStepId,
            String authorizationConfirmationId,
            Integer authorizationPlanVersion,
            String authorizationPayloadHash) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbc().update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO scheduled_task (
                            agent_task_id, plan_step_id, confirmation_item_id,
                            authorization_workflow_id, authorization_step_id,
                            authorization_confirmation_id, authorization_plan_version,
                            authorization_payload_hash, user_id,
                            task_type, payload_json, payload_hash, idempotency_key,
                            execute_at, timezone, status, attempt_count, locked_by,
                            locked_until, last_error_code, created_at, updated_at, version
                        ) VALUES (NULL, NULL, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0,
                                  NULL, NULL, NULL, ?, ?, 0)
                        """, Statement.RETURN_GENERATED_KEYS);
                statement.setString(1, authorizationWorkflowId);
                statement.setString(2, authorizationStepId);
                statement.setString(3, authorizationConfirmationId);
                if (authorizationPlanVersion == null) statement.setNull(4, java.sql.Types.INTEGER);
                else statement.setInt(4, authorizationPlanVersion);
                statement.setString(5, authorizationPayloadHash);
                statement.setLong(6, userId);
                statement.setString(7, taskType);
                statement.setString(8, payloadJson);
                statement.setString(9, payloadHash);
                statement.setString(10, idempotencyKey);
                statement.setTimestamp(11, timestamp(executeAt));
                statement.setString(12, timezone);
                statement.setTimestamp(13, timestamp(now));
                statement.setTimestamp(14, timestamp(now));
                return statement;
            }, keyHolder);
        } catch (DuplicateKeyException exception) {
            throw exception;
        }
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("定时任务写入后没有生成 ID");
        }
        return findById(key.longValue())
                .orElseThrow(() -> new IllegalStateException("定时任务写入后无法读取"));
    }

    public List<ScheduledTaskSnapshot> claimDueTasks(
            String workerId,
            Instant now,
            Instant leaseUntil,
            int limit) {
        return transactionTemplate().execute(status -> {
            List<Long> ids = jdbc().queryForList("""
                    SELECT id
                    FROM scheduled_task
                    WHERE execute_at <= ?
                      AND (
                          status = 'PENDING'
                          OR (
                              status IN ('CLAIMED', 'RUNNING')
                              AND locked_until IS NOT NULL
                              AND locked_until <= ?
                          )
                      )
                    ORDER BY id
                    LIMIT %s
                    FOR UPDATE SKIP LOCKED
                    """.formatted(safeLimit(limit)), Long.class, timestamp(now), timestamp(now));
            for (Long id : ids) {
                jdbc().update("""
                        UPDATE scheduled_task
                        SET status = 'CLAIMED',
                            locked_by = ?,
                            locked_until = ?,
                            attempt_count = attempt_count + 1,
                            updated_at = ?,
                            version = version + 1
                        WHERE id = ?
                        """, workerId, timestamp(leaseUntil), timestamp(now), id);
            }
            return ids.stream()
                    .map(id -> findById(id).orElseThrow())
                    .toList();
        });
    }

    public void recoverExpiredLeases(Instant now) {
        recoverExpiredLeasesInternal(now);
    }

    public boolean markRunning(long id, String workerId, Instant now) {
        return jdbc().update("""
                UPDATE scheduled_task
                SET status = 'RUNNING', updated_at = ?, version = version + 1
                WHERE id = ?
                  AND status = 'CLAIMED'
                  AND locked_by = ?
                  AND locked_until > ?
                """, timestamp(now), id, workerId, timestamp(now)) > 0;
    }

    public boolean markSucceeded(long id, String workerId, Instant now) {
        return jdbc().update("""
                UPDATE scheduled_task
                SET status = 'SUCCEEDED',
                    locked_by = NULL,
                    locked_until = NULL,
                    last_error_code = NULL,
                    updated_at = ?,
                    version = version + 1
                WHERE id = ? AND status = 'RUNNING' AND locked_by = ?
                """, timestamp(now), id, workerId) > 0;
    }

    public boolean markFailed(long id, String workerId, String errorCode, Instant now) {
        return jdbc().update("""
                UPDATE scheduled_task
                SET status = 'FAILED',
                    locked_by = NULL,
                    locked_until = NULL,
                    last_error_code = ?,
                    updated_at = ?,
                    version = version + 1
                WHERE id = ? AND status = 'RUNNING' AND locked_by = ?
                """, errorCode, timestamp(now), id, workerId) > 0;
    }

    public boolean cancel(long id, long userId, Instant now) {
        return jdbc().update("""
                UPDATE scheduled_task
                SET status = 'CANCELLED',
                    locked_by = NULL,
                    locked_until = NULL,
                    updated_at = ?,
                    version = version + 1
                WHERE id = ? AND user_id = ? AND status = 'PENDING'
                """, timestamp(now), id, userId) > 0;
    }

    private void recoverExpiredLeasesInternal(Instant now) {
        jdbc().update("""
                UPDATE scheduled_task
                SET status = 'PENDING',
                    locked_by = NULL,
                    locked_until = NULL,
                    updated_at = ?,
                    version = version + 1
                WHERE status IN ('CLAIMED', 'RUNNING')
                  AND locked_until IS NOT NULL
                  AND locked_until <= ?
                """, timestamp(now), timestamp(now));
    }

    private List<ScheduledTaskSnapshot> query(String sql, Object... arguments) {
        return jdbc().query(sql, rowMapper, arguments);
    }

    private ScheduledTaskSnapshot map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ScheduledTaskSnapshot(
                resultSet.getLong("id"),
                nullableLong(resultSet, "agent_task_id"),
                nullableLong(resultSet, "plan_step_id"),
                nullableLong(resultSet, "confirmation_item_id"),
                resultSet.getString("authorization_workflow_id"),
                resultSet.getString("authorization_step_id"),
                resultSet.getString("authorization_confirmation_id"),
                nullableInt(resultSet, "authorization_plan_version"),
                resultSet.getString("authorization_payload_hash"),
                resultSet.getString("task_type"),
                readJson(resultSet.getString("payload_json")),
                resultSet.getString("payload_hash"),
                resultSet.getString("idempotency_key"),
                instant(resultSet, "execute_at"),
                resultSet.getString("timezone"),
                resultSet.getString("status"),
                resultSet.getInt("attempt_count"),
                resultSet.getString("locked_by"),
                instant(resultSet, "locked_until"),
                resultSet.getString("last_error_code"),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at"),
                resultSet.getInt("version"));
    }

    private JsonNode readJson(String value) throws SQLException {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new SQLException("数据库中的定时任务 JSON 无法解析", exception);
        }
    }

    private Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private Integer nullableInt(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private int safeLimit(int limit) {
        return Math.max(1, Math.min(limit, 100));
    }

    private JdbcTemplate jdbc() {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null) {
            throw new IllegalStateException("定时任务持久化未启用：数据库连接不可用");
        }
        return jdbcTemplate;
    }

    private TransactionTemplate transactionTemplate() {
        TransactionTemplate current = transactionTemplate;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (transactionTemplate == null) {
                DataSource dataSource = jdbc().getDataSource();
                if (dataSource == null) {
                    throw new IllegalStateException("定时任务仓储需要 DataSource");
                }
                transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            }
            return transactionTemplate;
        }
    }

    private String columns() {
        return "id, agent_task_id, plan_step_id, confirmation_item_id, "
                + "authorization_workflow_id, authorization_step_id, authorization_confirmation_id, "
                + "authorization_plan_version, authorization_payload_hash, user_id, task_type, "
                + "payload_json, payload_hash, idempotency_key, execute_at, timezone, status, "
                + "attempt_count, locked_by, locked_until, last_error_code, created_at, updated_at, version";
    }
}
