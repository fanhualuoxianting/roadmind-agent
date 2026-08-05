package com.roadmind.server.preference;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class PreferenceService {

    private static final Set<String> CATEGORIES = Set.of(
            "LOCATION", "CLIMATE", "CHARGING", "REMINDER", "VEHICLE", "HOME_DEVICE");
    private static final Pattern KEY_PATTERN = Pattern.compile("[\\p{L}\\p{N}_-]{1,80}");

    private final ObjectProvider<PreferenceRepository> repositoryProvider;
    private final Clock clock = Clock.systemUTC();

    public PreferenceService(ObjectProvider<PreferenceRepository> repositoryProvider) {
        this.repositoryProvider = repositoryProvider;
    }

    public List<PreferenceSnapshot> list(String username, String category, int limit) {
        String normalizedCategory = category == null || category.isBlank()
                ? null
                : normalizeCategory(category);
        return repository().findActive(resolveUserId(username), normalizedCategory, limit, clock.instant());
    }

    public PreferenceSnapshot put(
            String username,
            String category,
            String preferenceKey,
            JsonNode value,
            String sensitivity,
            Instant expiresAt,
            String idempotencyKey) {
        String normalizedCategory = normalizeCategory(category);
        validateKey(preferenceKey);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("偏好 value 不能为空");
        }
        String normalizedSensitivity = sensitivity == null || sensitivity.isBlank()
                ? "NORMAL"
                : sensitivity.toUpperCase(Locale.ROOT);
        if (!Set.of("NORMAL", "SENSITIVE").contains(normalizedSensitivity)) {
            throw new IllegalArgumentException("sensitivity 只能是 NORMAL 或 SENSITIVE");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key 不能为空");
        }
        if (expiresAt != null && !expiresAt.isAfter(clock.instant())) {
            throw new IllegalArgumentException("expiresAt 必须是未来时间");
        }
        return repository().upsert(
                resolveUserId(username),
                normalizedCategory,
                preferenceKey,
                value,
                normalizedSensitivity,
                expiresAt,
                clock.instant());
    }

    public boolean delete(String username, String category, String preferenceKey, String idempotencyKey) {
        String normalizedCategory = normalizeCategory(category);
        validateKey(preferenceKey);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key 不能为空");
        }
        return repository().softDelete(
                resolveUserId(username), normalizedCategory, preferenceKey, clock.instant());
    }

    /**
     * Expands only active LOCATION aliases for the current request. It never writes a preference.
     */
    public String expandLocationAliases(String username, String message) {
        if (message == null || message.isBlank()) {
            return message;
        }
        PreferenceRepository repository = repositoryProvider.getIfAvailable();
        if (repository == null || !repository.isAvailable()) {
            return message;
        }
        List<PreferenceSnapshot> locations = new ArrayList<>(list(username, "LOCATION", 100));
        locations.sort(Comparator.comparingInt((PreferenceSnapshot item) -> item.preferenceKey().length())
                .reversed());
        String expanded = message;
        for (PreferenceSnapshot preference : locations) {
            String displayValue = displayValue(preference.value());
            if (displayValue != null && expanded.contains(preference.preferenceKey())) {
                expanded = expanded.replace(preference.preferenceKey(), displayValue);
            }
        }
        return expanded;
    }

    private long resolveUserId(String username) {
        return repository().requireUserId(username);
    }

    private PreferenceRepository repository() {
        PreferenceRepository repository = repositoryProvider.getIfAvailable();
        if (repository == null) {
            throw new IllegalStateException("偏好持久化未启用：数据库连接不可用");
        }
        return repository;
    }

    private String normalizeCategory(String category) {
        String normalized = category == null ? "" : category.toUpperCase(Locale.ROOT);
        if (!CATEGORIES.contains(normalized)) {
            throw new IllegalArgumentException("不支持的偏好分类: " + category);
        }
        return normalized;
    }

    private void validateKey(String preferenceKey) {
        if (preferenceKey == null || !KEY_PATTERN.matcher(preferenceKey).matches()) {
            throw new IllegalArgumentException("preferenceKey 只能包含中文、字母、数字、下划线或连字符");
        }
    }

    private String displayValue(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isTextual()) {
            return value.asText();
        }
        for (String field : List.of("name", "label", "displayName", "address")) {
            JsonNode candidate = value.get(field);
            if (candidate != null && candidate.isTextual() && !candidate.asText().isBlank()) {
                return candidate.asText();
            }
        }
        return null;
    }
}
