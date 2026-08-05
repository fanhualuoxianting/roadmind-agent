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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** MySQL-backed snapshot store for the confirmable Core Workflow. */
@Repository
public class CoreWorkflowPersistence {

    private static final Duration NON_CONFIRMATION_TTL = Duration.ofHours(24);

    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;
    private final ObjectMapper objectMapper;
    private final RowMapper<WorkflowRow> rowMapper = this::map;

    public CoreWorkflowPersistence(
            ObjectProvider<JdbcTemplate> jdbcTemplateProvider,
            ObjectMapper objectMapper) {
        this.jdbcTemplateProvider = jdbcTemplateProvider;
        this.objectMapper = objectMapper;
    }

    public boolean isAvailable() {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        return jdbcTemplate != null && jdbcTemplate.getDataSource() != null;
    }

    public void save(Snapshot snapshot) {
        if (!isAvailable()) {
            return;
        }
        Instant now = snapshot.updatedAt();
        Instant expiresAt = snapshot.confirmation() == null
                ? now.plus(NON_CONFIRMATION_TTL)
                : snapshot.confirmation().expiresAt();
        jdbc().update("""
                INSERT INTO workflow_state (
                    workflow_id, conversation_id, status, state_json,
                    expires_at, created_at, updated_at, version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 0)
                ON DUPLICATE KEY UPDATE
                    conversation_id = VALUES(conversation_id),
                    status = VALUES(status),
                    state_json = VALUES(state_json),
                    expires_at = VALUES(expires_at),
                    updated_at = VALUES(updated_at),
                    version = version + 1
                """,
                snapshot.workflowId(),
                snapshot.conversationId(),
                snapshot.status(),
                writeJson(snapshot),
                Timestamp.from(expiresAt),
                Timestamp.from(now),
                Timestamp.from(now));
    }

    public Optional<Snapshot> findById(String workflowId) {
        return query("SELECT state_json FROM workflow_state WHERE workflow_id = ?", workflowId)
                .stream()
                .findFirst()
                .map(this::readSnapshot);
    }

    public Optional<Snapshot> findLatestByConversation(String conversationId) {
        return query("SELECT state_json FROM workflow_state WHERE conversation_id = ? "
                + "ORDER BY updated_at DESC LIMIT 1", conversationId)
                .stream()
                .findFirst()
                .map(this::readSnapshot);
    }

    private List<WorkflowRow> query(String sql, Object... arguments) {
        return jdbc().query(sql, rowMapper, arguments);
    }

    private WorkflowRow map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new WorkflowRow(resultSet.getString("state_json"));
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

    private record WorkflowRow(String stateJson) {
    }
}
