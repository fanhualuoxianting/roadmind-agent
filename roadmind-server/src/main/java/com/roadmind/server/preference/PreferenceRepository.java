package com.roadmind.server.preference;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class PreferenceRepository {

    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;
    private final ObjectMapper objectMapper;
    private final RowMapper<PreferenceSnapshot> rowMapper = this::map;

    public PreferenceRepository(
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

    public List<PreferenceSnapshot> findActive(long userId, String category, int limit, Instant now) {
        String sql = """
                SELECT id, category, preference_key, value_json, sensitivity,
                       expires_at, created_at, updated_at, version
                FROM user_preference
                WHERE user_id = ?
                  AND deleted_at IS NULL
                  AND (expires_at IS NULL OR expires_at > ?)
                """;
        if (category == null) {
            sql += " ORDER BY id DESC LIMIT " + safeLimit(limit);
            return jdbc().query(sql, rowMapper, userId, timestamp(now));
        }
        sql += " AND category = ? ORDER BY id DESC LIMIT " + safeLimit(limit);
        return jdbc().query(sql, rowMapper, userId, timestamp(now), category);
    }

    public Optional<PreferenceSnapshot> findActiveByKey(
            long userId,
            String category,
            String preferenceKey,
            Instant now) {
        List<PreferenceSnapshot> rows = jdbc().query("""
                SELECT id, category, preference_key, value_json, sensitivity,
                       expires_at, created_at, updated_at, version
                FROM user_preference
                WHERE user_id = ?
                  AND category = ?
                  AND preference_key = ?
                  AND deleted_at IS NULL
                  AND (expires_at IS NULL OR expires_at > ?)
                """, rowMapper, userId, category, preferenceKey, timestamp(now));
        return rows.stream().findFirst();
    }

    public PreferenceSnapshot upsert(
            long userId,
            String category,
            String preferenceKey,
            JsonNode value,
            String sensitivity,
            Instant expiresAt,
            Instant now) {
        jdbc().update("""
                INSERT INTO user_preference (
                    user_id, category, preference_key, value_json, sensitivity,
                    expires_at, deleted_at, created_at, updated_at, version
                ) VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?, 0)
                ON DUPLICATE KEY UPDATE
                    value_json = VALUES(value_json),
                    sensitivity = VALUES(sensitivity),
                    expires_at = VALUES(expires_at),
                    deleted_at = NULL,
                    updated_at = VALUES(updated_at),
                    version = version + 1
                """,
                userId,
                category,
                preferenceKey,
                writeJson(value),
                sensitivity,
                timestampOrNull(expiresAt),
                timestamp(now),
                timestamp(now));
        return findActiveByKey(userId, category, preferenceKey, now)
                .orElseThrow(() -> new IllegalStateException("偏好写入后无法读取"));
    }

    public boolean softDelete(long userId, String category, String preferenceKey, Instant now) {
        return jdbc().update("""
                UPDATE user_preference
                SET deleted_at = ?, updated_at = ?, version = version + 1
                WHERE user_id = ?
                  AND category = ?
                  AND preference_key = ?
                  AND deleted_at IS NULL
                """, timestamp(now), timestamp(now), userId, category, preferenceKey) > 0;
    }

    private PreferenceSnapshot map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new PreferenceSnapshot(
                resultSet.getLong("id"),
                resultSet.getString("category"),
                resultSet.getString("preference_key"),
                readJson(resultSet.getString("value_json")),
                resultSet.getString("sensitivity"),
                instant(resultSet, "expires_at"),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at"),
                resultSet.getInt("version"));
    }

    private JsonNode readJson(String value) throws SQLException {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new SQLException("数据库中的偏好 JSON 无法解析", exception);
        }
    }

    private String writeJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("偏好值无法编码为 JSON", exception);
        }
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private Object timestampOrNull(Instant value) {
        return value == null ? null : timestamp(value);
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
            throw new IllegalStateException("偏好持久化未启用：数据库连接不可用");
        }
        return jdbcTemplate;
    }
}
