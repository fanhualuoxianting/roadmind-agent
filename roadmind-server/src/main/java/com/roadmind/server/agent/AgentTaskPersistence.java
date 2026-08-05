package com.roadmind.server.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Durable task snapshot and idempotency storage. Execution remains owned by the existing
 * in-memory workflow executor; this repository only makes the task fact recoverable.
 */
@Repository
public class AgentTaskPersistence {

    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;
    private final ObjectMapper objectMapper;
    private final RowMapper<TaskRow> rowMapper = this::map;

    public AgentTaskPersistence(
            ObjectProvider<JdbcTemplate> jdbcTemplateProvider,
            ObjectMapper objectMapper) {
        this.jdbcTemplateProvider = jdbcTemplateProvider;
        this.objectMapper = objectMapper;
    }

    public boolean isAvailable() {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        return jdbcTemplate != null && jdbcTemplate.getDataSource() != null;
    }

    public void create(
            String username,
            String conversationId,
            String idempotencyKey,
            String requestHash,
            AgentTaskSnapshot snapshot) {
        if (!isAvailable()) {
            return;
        }
        long userId = requireUserId(username);
        try {
            jdbc().update("""
                    INSERT INTO agent_task (
                        id, conversation_id, user_id, idempotency_key, request_hash,
                        goal, status, context_json, context_version, plan_version,
                        lock_version, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, 0, ?, ?)
                    """,
                    parseId(snapshot.taskId()),
                    parseId(conversationId),
                    userId,
                    idempotencyKey,
                    requestHash,
                    snapshot.goal(),
                    snapshot.status(),
                    writeJson(snapshot),
                    planVersion(snapshot),
                    timestamp(snapshot.createdAt()),
                    timestamp(snapshot.updatedAt()));
        } catch (DuplicateKeyException exception) {
            AgentTaskReplay replay = findByIdempotencyKey(userId, conversationId, idempotencyKey)
                    .orElseThrow(() -> exception);
            if (!requestHash.equals(replay.requestHash())) {
                throw new AgentIdempotencyConflictException();
            }
        }
    }

    public void save(AgentTaskSnapshot snapshot) {
        if (!isAvailable()) {
            return;
        }
        jdbc().update("""
                UPDATE agent_task
                SET goal = ?, status = ?, context_json = ?, plan_version = ?, updated_at = ?
                WHERE id = ?
                """,
                snapshot.goal(),
                snapshot.status(),
                writeJson(snapshot),
                planVersion(snapshot),
                timestamp(snapshot.updatedAt()),
                parseId(snapshot.taskId()));
    }

    public Optional<AgentTaskSnapshot> findById(String taskId) {
        return query("SELECT id, conversation_id, request_hash, goal, status, context_json, "
                + "created_at, updated_at FROM agent_task WHERE id = ?", parseId(taskId))
                .stream()
                .findFirst()
                .map(this::snapshot);
    }

    public Optional<AgentTaskSnapshot> findByIdForUser(String taskId, String username) {
        if (!isAvailable()) return Optional.empty();
        return query("SELECT id, conversation_id, request_hash, goal, status, context_json, "
                + "created_at, updated_at FROM agent_task "
                + "WHERE id = ? AND user_id = (SELECT id FROM `user` WHERE username = ? AND status = 'ACTIVE')",
                parseId(taskId),
                username)
                .stream()
                .findFirst()
                .map(this::snapshot);
    }

    public Optional<AgentTaskReplay> findByIdempotencyKey(
            String username,
            String conversationId,
            String idempotencyKey) {
        if (!isAvailable()) {
            return Optional.empty();
        }
        return findByIdempotencyKey(requireUserId(username), conversationId, idempotencyKey);
    }

    private Optional<AgentTaskReplay> findByIdempotencyKey(
            long userId,
            String conversationId,
            String idempotencyKey) {
        return query("SELECT id, conversation_id, request_hash, goal, status, context_json, "
                + "created_at, updated_at FROM agent_task "
                + "WHERE user_id = ? AND conversation_id = ? AND idempotency_key = ?",
                userId,
                parseId(conversationId),
                idempotencyKey)
                .stream()
                .findFirst()
                .map(row -> new AgentTaskReplay(row.requestHash(), snapshot(row)));
    }

    private long requireUserId(String username) {
        try {
            return jdbc().queryForObject(
                    "SELECT id FROM `user` WHERE username = ? AND status = 'ACTIVE'",
                    Long.class,
                    username);
        } catch (EmptyResultDataAccessException exception) {
            throw new IllegalStateException("当前用户未在数据库中注册: " + username, exception);
        }
    }

    private List<TaskRow> query(String sql, Object... arguments) {
        return jdbc().query(sql, rowMapper, arguments);
    }

    private TaskRow map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new TaskRow(
                resultSet.getLong("id"),
                resultSet.getLong("conversation_id"),
                resultSet.getString("request_hash"),
                resultSet.getString("goal"),
                resultSet.getString("status"),
                resultSet.getString("context_json"),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at"));
    }

    private AgentTaskSnapshot snapshot(TaskRow row) {
        try {
            JsonNode json = objectMapper.readTree(row.contextJson());
            List<AgentToolCallSnapshot> toolCalls = new ArrayList<>();
            JsonNode calls = json.path("toolCalls");
            if (calls.isArray()) {
                for (JsonNode call : calls) {
                    toolCalls.add(new AgentToolCallSnapshot(
                            text(call, "executionId"),
                            text(call, "toolName"),
                            text(call, "toolVersion"),
                            text(call, "status"),
                            call.path("attempts").asInt(),
                            call.path("durationMs").asLong(),
                            call.hasNonNull("result")
                                    ? objectMapper.convertValue(call.path("result"), Object.class)
                                    : null,
                            text(call, "errorCode"),
                            text(call, "errorMessage"),
                            text(call, "traceId")));
                }
            }
            return new AgentTaskSnapshot(
                    Long.toString(row.id()),
                    Long.toString(row.conversationId()),
                    row.goal(),
                    row.status(),
                    text(json, "plannerMode"),
                    text(json, "modelName"),
                    json.path("degraded").asBoolean(),
                    json.path("jsonRepaired").asBoolean(),
                    text(json, "response"),
                    List.copyOf(toolCalls),
                    json.path("completedToolCalls").asInt(toolCalls.size()),
                    json.path("totalToolCalls").asInt(),
                    row.createdAt(),
                    row.updatedAt());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("数据库中的 Agent 任务 JSON 无法解析", exception);
        }
    }

    private String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.path(field).asText() : null;
    }

    private String writeJson(AgentTaskSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Agent 任务快照无法编码为 JSON", exception);
        }
    }

    private int planVersion(AgentTaskSnapshot snapshot) {
        return snapshot.plannerMode() == null ? 0 : 1;
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private long parseId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Agent 任务/会话 ID 必须是数字", exception);
        }
    }

    private JdbcTemplate jdbc() {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null) {
            throw new IllegalStateException("Agent 任务持久化未启用：数据库连接不可用");
        }
        return jdbcTemplate;
    }

    private record TaskRow(
            long id,
            long conversationId,
            String requestHash,
            String goal,
            String status,
            String contextJson,
            Instant createdAt,
            Instant updatedAt) {
    }
}
