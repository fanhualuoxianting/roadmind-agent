package com.roadmind.server.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Best-effort Redis cache. A cache outage never replaces the MySQL source of truth.
 */
@Component
public class ConversationContextCache {

    private static final int SCHEMA_VERSION = 1;
    private static final Duration TTL = Duration.ofHours(24);

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final ObjectMapper objectMapper;

    public ConversationContextCache(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            ObjectMapper objectMapper) {
        this.redisTemplateProvider = redisTemplateProvider;
        this.objectMapper = objectMapper;
    }

    public void put(ConversationContextSnapshot snapshot) {
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis == null) {
            return;
        }
        try {
            redis.opsForValue().set(contextKey(snapshot.conversationId()), encode(snapshot), TTL);
            String messagesKey = messagesKey(snapshot.conversationId());
            redis.delete(messagesKey);
            if (!snapshot.recentMessages().isEmpty()) {
                redis.opsForList().rightPushAll(messagesKey, snapshot.recentMessages());
            }
            redis.expire(messagesKey, TTL);
        } catch (JsonProcessingException | RuntimeException ignored) {
            // Redis is an optimization; the MySQL write has already succeeded.
        }
    }

    public Optional<ConversationContextSnapshot> get(String conversationId) {
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis == null) {
            return Optional.empty();
        }
        try {
            String value = redis.opsForValue().get(contextKey(conversationId));
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            JsonNode json = objectMapper.readTree(value);
            if (json.path("schemaVersion").asInt() != SCHEMA_VERSION
                    || !conversationId.equals(json.path("conversationId").asText())) {
                return Optional.empty();
            }
            Instant expiresAt = json.hasNonNull("expiresAt")
                    ? Instant.parse(json.path("expiresAt").asText())
                    : null;
            if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
                return Optional.empty();
            }

            List<String> messages = new ArrayList<>();
            JsonNode cachedMessages = json.path("recentMessages");
            if (cachedMessages.isArray()) {
                for (JsonNode node : cachedMessages) {
                    messages.add(node.asText());
                }
            }
            List<String> listMessages = redis.opsForList().range(messagesKey(conversationId), 0, -1);
            if (listMessages != null && !listMessages.isEmpty()) {
                messages = List.copyOf(listMessages);
            }
            return Optional.of(new ConversationContextSnapshot(
                    json.path("userId").asLong(),
                    conversationId,
                    json.path("title").asText(),
                    json.path("status").asText(),
                    json.path("timezone").asText(),
                    Instant.parse(json.path("createdAt").asText()),
                    json.path("contextVersion").asInt(),
                    messages,
                    expiresAt));
        } catch (JsonProcessingException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private String encode(ConversationContextSnapshot snapshot) throws JsonProcessingException {
        return objectMapper.writeValueAsString(new CacheValue(
                SCHEMA_VERSION,
                snapshot.userId(),
                snapshot.conversationId(),
                snapshot.title(),
                snapshot.status(),
                snapshot.timezone(),
                snapshot.createdAt(),
                snapshot.contextVersion(),
                snapshot.recentMessages(),
                snapshot.expiresAt()));
    }

    private String contextKey(String conversationId) {
        return "roadmind:session:" + conversationId + ":context";
    }

    private String messagesKey(String conversationId) {
        return "roadmind:session:" + conversationId + ":recent-messages";
    }

    private record CacheValue(
            int schemaVersion,
            long userId,
            String conversationId,
            String title,
            String status,
            String timezone,
            Instant createdAt,
            int contextVersion,
            List<String> recentMessages,
            Instant expiresAt) {
    }
}
