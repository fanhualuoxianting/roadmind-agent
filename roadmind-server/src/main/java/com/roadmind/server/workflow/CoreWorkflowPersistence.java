package com.roadmind.server.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roadmind.server.workflow.CoreWorkflowModels.Snapshot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** MySQL-backed, user-scoped snapshot store for the confirmable Core Workflow. */
@Repository
public class CoreWorkflowPersistence {

    private static final Duration NON_CONFIRMATION_TTL = Duration.ofHours(24);
    private static final String DEMO_USERNAME = "roadmind-demo";

    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;
    private final ObjectMapper objectMapper;
    private final RowMapper<WorkflowRow> rowMapper = this::map;
    private final RowMapper<OwnedWorkflowRow> ownedRowMapper = this::mapOwned;

    public CoreWorkflowPersistence(
            ObjectProvider<JdbcTemplate> jdbcTemplateProvider,
            ObjectMapper objectMapper) {
        this.jdbcTemplateProvider = jdbcTemplateProvider;
        this.objectMapper = objectMapper;
    }

    /** Returns whether JDBC is configured, not whether MySQL is currently healthy. */
    public boolean isAvailable() {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        return jdbcTemplate != null && jdbcTemplate.getDataSource() != null;
    }

    public void save(String username, Snapshot snapshot) {
        if (!isAvailable()) {
            return;
        }
        try {
            long userId = requireUserId(username);
            Instant now = snapshot.updatedAt();
            Instant expiresAt = snapshot.confirmation() == null
                    ? now.plus(NON_CONFIRMATION_TTL)
                    : snapshot.confirmation().expiresAt();
            jdbc().update("""
                    INSERT INTO workflow_state (
                        workflow_id, conversation_id, user_id, status, state_json,
                        expires_at, created_at, updated_at, version
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)
                    ON DUPLICATE KEY UPDATE
                        conversation_id = VALUES(conversation_id),
                        user_id = VALUES(user_id),
                        status = VALUES(status),
                        state_json = VALUES(state_json),
                        expires_at = VALUES(expires_at),
                        updated_at = VALUES(updated_at),
                        version = version + 1
                    """,
                    snapshot.workflowId(),
                    snapshot.conversationId(),
                    userId,
                    snapshot.status(),
                    writeJson(snapshot),
                    Timestamp.from(expiresAt),
                    Timestamp.from(now),
                    Timestamp.from(now));
        } catch (DataAccessResourceFailureException ignored) {
            // The caller still writes the user-scoped Redis projection.
        }
    }

    void save(Snapshot snapshot) {
        save(DEMO_USERNAME, snapshot);
    }

    public Optional<Snapshot> findByIdForUser(String workflowId, String username) {
        if (!isAvailable()) return Optional.empty();
        try {
            return query("SELECT state_json FROM workflow_state "
                            + "WHERE workflow_id = ? "
                            + "AND user_id = (SELECT id FROM `user` WHERE username = ? AND status = 'ACTIVE')",
                    workflowId,
                    username)
                    .stream()
                    .findFirst()
                    .map(this::readSnapshot);
        } catch (DataAccessResourceFailureException ignored) {
            return Optional.empty();
        }
    }

    public Optional<Snapshot> findLatestByConversationForUser(String conversationId, String username) {
        if (!isAvailable()) return Optional.empty();
        try {
            return query("SELECT state_json FROM workflow_state "
                            + "WHERE conversation_id = ? "
                            + "AND user_id = (SELECT id FROM `user` WHERE username = ? AND status = 'ACTIVE') "
                            + "ORDER BY updated_at DESC LIMIT 1",
                    conversationId,
                    username)
                    .stream()
                    .findFirst()
                    .map(this::readSnapshot);
        } catch (DataAccessResourceFailureException ignored) {
            return Optional.empty();
        }
    }

    Optional<OwnedSnapshot> findOwnedById(String workflowId) {
        if (!isAvailable()) return Optional.empty();
        try {
            return jdbc().query("""
                            SELECT workflow.state_json, owner.username
                            FROM workflow_state AS workflow
                            JOIN `user` AS owner ON owner.id = workflow.user_id AND owner.status = 'ACTIVE'
                            WHERE workflow.workflow_id = ?
                            """,
                    ownedRowMapper,
                    workflowId)
                    .stream()
                    .findFirst()
                    .map(row -> new OwnedSnapshot(row.username(), readSnapshot(new WorkflowRow(row.stateJson()))));
        } catch (DataAccessResourceFailureException ignored) {
            return Optional.empty();
        }
    }

    Optional<Snapshot> findById(String workflowId) {
        return findByIdForUser(workflowId, DEMO_USERNAME);
    }

    Optional<Snapshot> findLatestByConversation(String conversationId) {
        return findLatestByConversationForUser(conversationId, DEMO_USERNAME);
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

    private List<WorkflowRow> query(String sql, Object... arguments) {
        return jdbc().query(sql, rowMapper, arguments);
    }

    private WorkflowRow map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new WorkflowRow(resultSet.getString("state_json"));
    }

    private OwnedWorkflowRow mapOwned(ResultSet resultSet, int rowNumber) throws SQLException {
        return new OwnedWorkflowRow(
                resultSet.getString("username"),
                resultSet.getString("state_json"));
    }

    private Snapshot readSnapshot(WorkflowRow row) {
        try {
            return objectMapper.readValue(row.stateJson(), Snapshot.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("数据库中的工作流快照无法解析", exception);
        }
    }

    private String writeJson(Snapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("工作流快照无法编码为 JSON", exception);
        }
    }

    private JdbcTemplate jdbc() {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null) {
            throw new IllegalStateException("工作流快照持久化未启用：数据库连接不可用");
        }
        return jdbcTemplate;
    }

    record OwnedSnapshot(String username, Snapshot snapshot) {
    }

    private record WorkflowRow(String stateJson) {
    }

    private record OwnedWorkflowRow(String username, String stateJson) {
    }
}
