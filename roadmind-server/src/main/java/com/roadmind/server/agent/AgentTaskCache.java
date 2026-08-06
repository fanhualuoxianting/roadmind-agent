package com.roadmind.server.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Best-effort Redis projections for task snapshots and request idempotency. */
@Component
public class AgentTaskCache {

    private static final int SCHEMA_VERSION = 2;
    private static final Duration TTL = Duration.ofHours(24);

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final ObjectMapper objectMapper;

    public AgentTaskCache(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            ObjectMapper objectMapper) {
        this.redisTemplateProvider = redisTemplateProvider;
        this.objectMapper = objectMapper;
    }

    public void putSnapshot(String userId, AgentTaskSnapshot snapshot) {
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis == null || userId == null || userId.isBlank()) {
            return;
        }
        try {
            redis.opsForValue().set(
                    snapshotKey(userId, snapshot.taskId()),
                    objectMapper.writeValueAsString(new SnapshotValue(
                            SCHEMA_VERSION,
                            userId,
                            snapshot)),
                    TTL);
        } catch (JsonProcessingException | RuntimeException ignored) {
            // Redis is only a recovery optimization; MySQL remains authoritative.
        }
    }

    public Optional<AgentTaskSnapshot> getSnapshot(String userId, String taskId) {
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis == null || userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        try {
            String value = redis.opsForValue().get(snapshotKey(userId, taskId));
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            SnapshotValue cached = objectMapper.readValue(value, SnapshotValue.class);
            if (cached.schemaVersion() != SCHEMA_VERSION
                    || !userId.equals(cached.userId())
                    || cached.snapshot() == null
                    || !taskId.equals(cached.snapshot().taskId())) {
                return Optional.empty();
            }
            return Optional.of(cached.snapshot());
        } catch (JsonProcessingException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    public void putIdempotency(
            String userId,
            String conversationId,
            String idempotencyKey,
            String requestHash,
            String taskId) {
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis == null
                || userId == null || userId.isBlank()
                || conversationId == null || conversationId.isBlank()) {
            return;
        }
        try {
            redis.opsForValue().set(
                    idempotencyKey(userId, conversationId, idempotencyKey),
                    objectMapper.writeValueAsString(new ReplayValue(
                            SCHEMA_VERSION,
                            userId,
                            conversationId,
                            requestHash,
                            taskId)),
                    TTL);
        } catch (JsonProcessingException | RuntimeException ignored) {
            // A cache failure must never fail an already durable task write.
        }
    }

    public Optional<AgentTaskReplay> findIdempotency(
            String userId,
            String conversationId,
            String idempotencyKey) {
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis == null
                || userId == null || userId.isBlank()
                || conversationId == null || conversationId.isBlank()) {
            return Optional.empty();
        }
        try {
            String value = redis.opsForValue().get(idempotencyKey(userId, conversationId, idempotencyKey));
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            ReplayValue cached = objectMapper.readValue(value, ReplayValue.class);
            if (cached.schemaVersion() != SCHEMA_VERSION
                    || !userId.equals(cached.userId())
                    || !conversationId.equals(cached.conversationId())
                    || cached.requestHash() == null
                    || cached.taskId() == null) {
                return Optional.empty();
            }
            return getSnapshot(userId, cached.taskId())
                    .map(snapshot -> new AgentTaskReplay(cached.requestHash(), snapshot));
        } catch (JsonProcessingException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private String snapshotKey(String userId, String taskId) {
        return "roadmind:agent-task:" + userId + ':' + taskId + ":snapshot";
    }

    private String idempotencyKey(String userId, String conversationId, String idempotencyKey) {
        return "roadmind:idempotency:agent-task:" + userId + ':' + conversationId + ':' + idempotencyKey;
    }

    private record SnapshotValue(int schemaVersion, String userId, AgentTaskSnapshot snapshot) {
    }

    private record ReplayValue(
            int schemaVersion,
            String userId,
            String conversationId,
            String requestHash,
            String taskId) {
    }
}
