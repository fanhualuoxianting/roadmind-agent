package com.roadmind.server.trip;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Repository;

/** MySQL fact store for the current Trip route and the latest recovery snapshot. */
@Repository
public class TripPersistence {

    private static final AtomicLong ID_SEQUENCE = new AtomicLong();

    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;
    private final ObjectMapper objectMapper;
    private volatile TransactionTemplate transactionTemplate;
    private final RowMapper<TripRow> rowMapper = this::map;

    public TripPersistence(
            ObjectProvider<JdbcTemplate> jdbcTemplateProvider,
            ObjectMapper objectMapper) {
        this.jdbcTemplateProvider = jdbcTemplateProvider;
        this.objectMapper = objectMapper;
    }

    public boolean isAvailable() {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        return jdbcTemplate != null && jdbcTemplate.getDataSource() != null;
    }

    public synchronized void save(TripSnapshot snapshot) {
        if (!isAvailable()) return;
        transactionTemplate().executeWithoutResult(status -> saveInternal(snapshot, null));
    }

    public synchronized void saveAndAppendEvents(
            TripSnapshot snapshot,
            List<TripEventEnvelope> events) {
        saveAndAppendEvents(snapshot, events, null);
    }

    public synchronized void saveAndAppendEvents(
            TripSnapshot snapshot,
            List<TripEventEnvelope> events,
            Long ownerUserId) {
        if (!isAvailable()) return;
        transactionTemplate().executeWithoutResult(status -> {
            saveInternal(snapshot, ownerUserId);
            Instant now = Instant.now();
            for (TripEventEnvelope event : events) {
                jdbc().update("""
                        INSERT INTO domain_event_outbox (
                            aggregate_type, aggregate_id, event_id, sequence, event_type, trace_id,
                            payload_json, status, available_at, locked_by, locked_until,
                            attempt_count, last_error_code, delivered_at, created_at, updated_at
                        ) VALUES ('TRIP', ?, ?, ?, ?, ?, ?, 'PENDING', ?, NULL, NULL,
                                  0, NULL, NULL, ?, ?)
                        """,
                        event.tripId(), event.eventId(), event.sequence(), event.type(), event.traceId(),
                        writeJson(event.data()), timestamp(event.occurredAt()), timestamp(now), timestamp(now));
            }
        });
    }

    public Optional<Long> findUserId(String username) {
        if (!isAvailable() || username == null || username.isBlank()) return Optional.empty();
        try {
            return Optional.ofNullable(jdbc().queryForObject(
                    "SELECT id FROM `user` WHERE username = ? AND status = 'ACTIVE'",
                    Long.class,
                    username));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    public Optional<TripSnapshot> findByIdForUser(String tripId, String username) {
        if (!isAvailable()) return Optional.empty();
        return jdbc().query(
                        "SELECT t.latest_snapshot FROM trip t "
                                + "LEFT JOIN `user` u ON u.id = t.owner_user_id "
                                + "WHERE t.public_id = ? AND (t.owner_user_id IS NULL "
                                + "OR (u.username = ? AND u.status = 'ACTIVE'))",
                        rowMapper,
                        tripId,
                        username)
                .stream()
                .findFirst()
                .map(this::readSnapshot);
    }

    public boolean isOwnedBy(String tripId, String username) {
        return findByIdForUser(tripId, username).isPresent();
    }

    private void saveInternal(TripSnapshot snapshot, Long ownerUserId) {
        if (!isAvailable()) {
            return;
        }
        RoutePlan route = snapshot.route();
        long candidateRouteId = nextId();
        Instant now = Instant.now();
        jdbc().update("""
                INSERT INTO route_plan (
                    id, public_id, route_version, provider, source_mode, coordinate_system,
                    origin_name, destination_name, distance_meters, duration_seconds,
                    route_hash, normalized_route, fetched_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    provider = VALUES(provider),
                    source_mode = VALUES(source_mode),
                    coordinate_system = VALUES(coordinate_system),
                    origin_name = VALUES(origin_name),
                    destination_name = VALUES(destination_name),
                    distance_meters = VALUES(distance_meters),
                    duration_seconds = VALUES(duration_seconds),
                    route_hash = VALUES(route_hash),
                    normalized_route = VALUES(normalized_route),
                    fetched_at = VALUES(fetched_at)
                """,
                candidateRouteId,
                route.routePlanId(),
                route.routeVersion(),
                fit(route.provider(), 32),
                fit(route.sourceMode(), 16),
                fit(route.coordinateSystem(), 16),
                fit(route.origin().name(), 120),
                fit(route.destination().name(), 120),
                route.distanceMeters(),
                route.durationSeconds(),
                route.routeHash(),
                writeJson(route),
                timestamp(route.fetchedAt() == null ? now : route.fetchedAt()));

        long routePlanId = jdbc().queryForObject(
                "SELECT id FROM route_plan WHERE public_id = ? AND route_version = ?",
                Long.class,
                route.routePlanId(),
                route.routeVersion());

        jdbc().update("""
                INSERT INTO trip (
                    id, public_id, owner_user_id, vehicle_public_id, current_route_plan_id, status,
                    simulation_speed, last_sequence, latest_snapshot, version,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                ON DUPLICATE KEY UPDATE
                    owner_user_id = COALESCE(VALUES(owner_user_id), owner_user_id),
                    vehicle_public_id = VALUES(vehicle_public_id),
                    current_route_plan_id = VALUES(current_route_plan_id),
                    status = VALUES(status),
                    simulation_speed = VALUES(simulation_speed),
                    last_sequence = VALUES(last_sequence),
                    latest_snapshot = VALUES(latest_snapshot),
                    updated_at = VALUES(updated_at),
                    version = version + 1
                """,
                nextId(),
                snapshot.tripId(),
                ownerUserId,
                snapshot.vehicleId(),
                routePlanId,
                snapshot.status().name(),
                snapshot.telemetry().simulationSpeed(),
                snapshot.telemetry().sequence(),
                writeJson(snapshot),
                timestamp(now),
                timestamp(now));
    }

    public Optional<TripSnapshot> findById(String tripId) {
        if (!isAvailable()) {
            return Optional.empty();
        }
        return jdbc().query(
                        "SELECT latest_snapshot FROM trip WHERE public_id = ?",
                        rowMapper,
                        tripId)
                .stream()
                .findFirst()
                .map(this::readSnapshot);
    }

    public List<TripSnapshot> findActive() {
        if (!isAvailable()) {
            return List.of();
        }
        return jdbc().query(
                        "SELECT latest_snapshot FROM trip "
                                + "WHERE status NOT IN ('COMPLETED', 'CANCELLED', 'FAILED') "
                                + "ORDER BY updated_at ASC",
                        rowMapper)
                .stream()
                .map(this::readSnapshot)
                .toList();
    }

    public List<TripSnapshot> findActiveForUser(String username) {
        if (!isAvailable()) {
            return List.of();
        }
        if (username == null || username.isBlank()) {
            return findActive();
        }
        return jdbc().query(
                        "SELECT t.latest_snapshot FROM trip t "
                                + "LEFT JOIN `user` u ON u.id = t.owner_user_id "
                                + "WHERE t.status NOT IN ('COMPLETED', 'CANCELLED', 'FAILED') "
                                + "AND (t.owner_user_id IS NULL "
                                + "OR (u.username = ? AND u.status = 'ACTIVE')) "
                                + "ORDER BY (t.owner_user_id IS NULL) ASC, t.updated_at DESC",
                        rowMapper,
                        username)
                .stream()
                .map(this::readSnapshot)
                .toList();
    }

    public long findMaxEventSequence(String tripId) {
        if (!isAvailable()) return 0;
        Long value = jdbc().queryForObject(
                "SELECT COALESCE(MAX(sequence), 0) FROM domain_event_outbox "
                        + "WHERE aggregate_type = 'TRIP' AND aggregate_id = ?",
                Long.class,
                tripId);
        return value == null ? 0 : value;
    }

    private TripRow map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new TripRow(resultSet.getString("latest_snapshot"));
    }

    private TripSnapshot readSnapshot(TripRow row) {
        try {
            return objectMapper.readValue(row.latestSnapshot(), TripSnapshot.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("数据库中的 Trip 快照无法解析", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Trip 快照无法编码为 JSON", exception);
        }
    }

    private Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private String fit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private long nextId() {
        long sequence = ID_SEQUENCE.incrementAndGet();
        long base = System.currentTimeMillis() * 1_000L + ThreadLocalRandom.current().nextInt(1_000);
        return base + sequence;
    }

    private JdbcTemplate jdbc() {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null) {
            throw new IllegalStateException("Trip 持久化未启用：数据库连接不可用");
        }
        return jdbcTemplate;
    }

    private TransactionTemplate transactionTemplate() {
        TransactionTemplate current = transactionTemplate;
        if (current != null) return current;
        synchronized (this) {
            if (transactionTemplate == null) {
                DataSource dataSource = jdbc().getDataSource();
                if (dataSource == null) throw new IllegalStateException("Trip 持久化需要 DataSource");
                transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            }
            return transactionTemplate;
        }
    }

    private record TripRow(String latestSnapshot) {
    }
}
