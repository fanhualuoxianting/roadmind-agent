package com.roadmind.server.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Writes a small, field-whitelisted audit trail without storing secrets or raw tool results. */
@Service
public class AuditService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuditService.class);
    private static final AtomicLong ID_SEQUENCE = new AtomicLong();
    private static final int MAX_STRING_LENGTH = 512;

    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;
    private final ObjectMapper objectMapper;

    public AuditService(ObjectProvider<JdbcTemplate> jdbcTemplateProvider, ObjectMapper objectMapper) {
        this.jdbcTemplateProvider = jdbcTemplateProvider;
        this.objectMapper = objectMapper;
    }

    public void record(
            String eventType,
            String actorType,
            String traceId,
            String taskId,
            Map<String, ?> detail) {
        JdbcTemplate jdbc = jdbcTemplateProvider.getIfAvailable();
        if (jdbc == null || jdbc.getDataSource() == null) return;

        try {
            Long agentTaskId = parseNullableLong(taskId);
            jdbc.update("""
                    INSERT INTO audit_event (
                        id, agent_task_id, event_type, actor_type, trace_id, detail_json, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    nextId(),
                    agentTaskId == null ? null : agentTaskId,
                    fit(eventType, 80),
                    fit(actorType, 32),
                    fit(traceId == null ? "none" : traceId, 64),
                    writeJson(redact(detail)),
                    java.sql.Timestamp.from(Instant.now()));
        } catch (DataAccessException | IllegalArgumentException exception) {
            // Audit must never turn a user request into a 500 during a database outage.
            LOGGER.warn("audit write failed eventType={} taskId={}", fit(eventType, 80), fit(taskId, 64));
        }
    }

    private Map<String, Object> redact(Map<String, ?> source) {
        Map<String, Object> target = new LinkedHashMap<>();
        if (source == null) return target;
        source.forEach((key, value) -> {
            String safeKey = fit(key, 80);
            target.put(safeKey, redactValue(safeKey, value, 0));
        });
        return target;
    }

    private Object redactValue(String key, Object value, int depth) {
        if (value == null) return null;
        String normalized = key == null ? "" : key.toLowerCase();
        if (normalized.contains("token") || normalized.contains("password")
                || normalized.contains("secret") || normalized.contains("apikey")
                || normalized.contains("authorization") || normalized.contains("cookie")) {
            return "[REDACTED]";
        }
        if (depth >= 2) return fit(String.valueOf(value), MAX_STRING_LENGTH);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.entrySet().stream().limit(24).forEach(entry -> {
                String nestedKey = fit(String.valueOf(entry.getKey()), 80);
                nested.put(nestedKey, redactValue(nestedKey, entry.getValue(), depth + 1));
            });
            return nested;
        }
        if (value instanceof List<?> list) {
            return list.stream().limit(24)
                    .map(item -> redactValue(key, item, depth + 1))
                    .toList();
        }
        if (value instanceof Number || value instanceof Boolean) return value;
        return fit(String.valueOf(value), MAX_STRING_LENGTH);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return "{}";
        }
    }

    private Long parseNullableLong(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private long nextId() {
        return System.currentTimeMillis() * 1_000L + ID_SEQUENCE.incrementAndGet();
    }

    private String fit(String value, int maxLength) {
        if (value == null) return "";
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
