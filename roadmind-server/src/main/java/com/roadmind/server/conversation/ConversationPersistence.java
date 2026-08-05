package com.roadmind.server.conversation;

import com.roadmind.server.agent.ConversationSnapshot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MySQL is the source of truth for conversation metadata and user messages.
 */
@Repository
public class ConversationPersistence {

    private static final Duration CONTEXT_TTL = Duration.ofHours(24);
    private static final String CONVERSATION_COLUMNS = "id, user_id, title, timezone, "
            + "context_version, status, created_at, context_summary, last_message_at, expires_at";

    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;
    private final RowMapper<ConversationRow> conversationRowMapper = this::mapConversation;
    private volatile TransactionTemplate transactionTemplate;

    public ConversationPersistence(ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        this.jdbcTemplateProvider = jdbcTemplateProvider;
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

    public Optional<ConversationContextSnapshot> create(ConversationSnapshot conversation, String username) {
        long userId = requireUserId(username);
        Instant createdAt = conversation.createdAt();
        Instant expiresAt = createdAt.plus(CONTEXT_TTL);
        long conversationId = parseId(conversation.conversationId());
        try {
            jdbc().update("""
                    INSERT INTO conversation (
                        id, user_id, title, timezone, context_version, status,
                        created_at, updated_at, context_summary, last_message_at, expires_at
                    ) VALUES (?, ?, ?, ?, 0, ?, ?, ?, NULL, NULL, ?)
                    """,
                    conversationId,
                    userId,
                    conversation.title(),
                    conversation.timezone(),
                    conversation.status(),
                    timestamp(createdAt),
                    timestamp(createdAt),
                    timestamp(expiresAt));
        } catch (DuplicateKeyException exception) {
            Optional<ConversationContextSnapshot> existing = findActive(userId, conversation.conversationId(), Instant.now());
            if (existing.isEmpty()
                    || !existing.get().title().equals(conversation.title())
                    || !existing.get().timezone().equals(conversation.timezone())) {
                throw exception;
            }
            return existing;
        }
        Optional<ConversationContextSnapshot> created = findActive(
                userId,
                conversation.conversationId(),
                createdAt);
        if (created.isEmpty()) {
            throw new IllegalStateException("会话写入后无法读取");
        }
        return created;
    }

    public Optional<ConversationContextSnapshot> findActive(
            long userId,
            String conversationId,
            Instant now) {
        ConversationRow row = jdbc().query(
                        "SELECT " + CONVERSATION_COLUMNS
                                + " FROM conversation"
                                + " WHERE id = ? AND user_id = ? AND status = 'ACTIVE'"
                                + " AND (expires_at IS NULL OR expires_at > ?)",
                        conversationRowMapper,
                        parseId(conversationId),
                        userId,
                        timestamp(now))
                .stream()
                .findFirst()
                .orElse(null);
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(toSnapshot(row, recentMessages(row.id())));
    }

    public Optional<ConversationContextSnapshot> appendUserMessage(
            long userId,
            String conversationId,
            String content,
            Instant now) {
        long id = parseId(conversationId);
        ConversationContextSnapshot snapshot = transactionTemplate().execute(status -> {
            int updated = jdbc().update("""
                    UPDATE conversation
                    SET context_version = context_version + 1,
                        context_summary = ?,
                        last_message_at = ?,
                        updated_at = ?
                    WHERE id = ?
                      AND user_id = ?
                      AND status = 'ACTIVE'
                      AND (expires_at IS NULL OR expires_at > ?)
                    """,
                    content,
                    timestamp(now),
                    timestamp(now),
                    id,
                    userId,
                    timestamp(now));
            if (updated == 0) {
                return null;
            }

            Integer contextVersion = jdbc().queryForObject(
                    "SELECT context_version FROM conversation WHERE id = ? AND user_id = ?",
                    Integer.class,
                    id,
                    userId);
            jdbc().update("""
                    INSERT INTO message (conversation_id, role, content, context_version, created_at)
                    VALUES (?, 'USER', ?, ?, ?)
                    """,
                    id,
                    content,
                    contextVersion,
                    timestamp(now));

            ConversationRow row = jdbc().query(
                            "SELECT " + CONVERSATION_COLUMNS + " FROM conversation WHERE id = ? AND user_id = ?",
                            conversationRowMapper,
                            id,
                            userId)
                    .stream()
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("消息写入后无法读取会话"));
            return toSnapshot(row, recentMessages(id));
        });
        return Optional.ofNullable(snapshot);
    }

    private List<String> recentMessages(long conversationId) {
        List<String> messages = jdbc().query(
                        "SELECT content FROM message WHERE conversation_id = ? ORDER BY id DESC LIMIT 20",
                        (resultSet, rowNumber) -> resultSet.getString("content"),
                        conversationId);
        List<String> ordered = new ArrayList<>(messages);
        java.util.Collections.reverse(ordered);
        return List.copyOf(ordered);
    }

    private ConversationContextSnapshot toSnapshot(ConversationRow row, List<String> messages) {
        return new ConversationContextSnapshot(
                row.userId(),
                Long.toString(row.id()),
                row.title(),
                row.status(),
                row.timezone(),
                row.createdAt(),
                row.contextVersion(),
                messages,
                row.expiresAt());
    }

    private ConversationRow mapConversation(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ConversationRow(
                resultSet.getLong("id"),
                resultSet.getLong("user_id"),
                resultSet.getString("title"),
                resultSet.getString("timezone"),
                resultSet.getInt("context_version"),
                resultSet.getString("status"),
                instant(resultSet, "created_at"),
                instant(resultSet, "last_message_at"),
                instant(resultSet, "expires_at"));
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private long parseId(String conversationId) {
        try {
            return Long.parseLong(conversationId);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("会话 ID 必须是数字", exception);
        }
    }

    private JdbcTemplate jdbc() {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null) {
            throw new IllegalStateException("会话持久化未启用：数据库连接不可用");
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
                if (!isAvailable()) {
                    throw new IllegalStateException("会话持久化需要 DataSource");
                }
                transactionTemplate = new TransactionTemplate(
                        new DataSourceTransactionManager(jdbc().getDataSource()));
            }
            return transactionTemplate;
        }
    }

    private record ConversationRow(
            long id,
            long userId,
            String title,
            String timezone,
            int contextVersion,
            String status,
            Instant createdAt,
            Instant lastMessageAt,
            Instant expiresAt) {
    }
}
