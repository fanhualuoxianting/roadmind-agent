package com.roadmind.server.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roadmind.server.workflow.CoreWorkflowModels.Confirmation;
import com.roadmind.server.workflow.CoreWorkflowModels.Slot;
import com.roadmind.server.workflow.CoreWorkflowModels.Snapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Best-effort user-scoped Redis projections for the Core Workflow. */
@Component
public class CoreWorkflowCache {

    private static final int SCHEMA_VERSION = 2;
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);
    private static final Duration MIN_TTL = Duration.ofSeconds(1);
    private static final String DEMO_USERNAME = "roadmind-demo";

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public CoreWorkflowCache(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            ObjectMapper objectMapper) {
        this(redisTemplateProvider, objectMapper, Clock.systemUTC());
    }

    CoreWorkflowCache(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            ObjectMapper objectMapper,
            Clock clock) {
        this.redisTemplateProvider = redisTemplateProvider;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public void put(String username, Snapshot snapshot) {
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis == null || invalid(username) || snapshot == null) {
            return;
        }
        try {
            Duration stateTtl = ttl(snapshot);
            redis.opsForValue().set(
                    stateKey(username, snapshot.workflowId()),
                    objectMapper.writeValueAsString(new StateValue(
                            SCHEMA_VERSION, username, snapshot.workflowId(), snapshot)),
                    stateTtl);
            redis.opsForValue().set(
                    slotsKey(username, snapshot.workflowId()),
                    objectMapper.writeValueAsString(new SlotsValue(
                            SCHEMA_VERSION,
                            username,
                            snapshot.workflowId(),
                            snapshot.contextVersion(),
                            snapshot.slots())),
                    DEFAULT_TTL);

            Confirmation confirmation = snapshot.confirmation();
            if (confirmation != null && "PENDING".equals(confirmation.status())) {
                redis.opsForValue().set(
                        confirmationKey(username, snapshot.workflowId()),
                        objectMapper.writeValueAsString(new ConfirmationValue(
                                SCHEMA_VERSION,
                                username,
                                snapshot.workflowId(),
                                confirmation)),
                        ttl(snapshot));
            } else {
                redis.delete(confirmationKey(username, snapshot.workflowId()));
            }
            redis.opsForValue().set(
                    conversationIndexKey(username, snapshot.conversationId()),
                    snapshot.workflowId(),
                    DEFAULT_TTL);
        } catch (JsonProcessingException | RuntimeException ignored) {
            // Redis is an optimization; MySQL remains authoritative when available.
        }
    }

    void put(Snapshot snapshot) {
        put(DEMO_USERNAME, snapshot);
    }

    public Optional<Snapshot> get(String username, String workflowId) {
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis == null || invalid(username) || invalid(workflowId)) {
            return Optional.empty();
        }
        try {
            String value = redis.opsForValue().get(stateKey(username, workflowId));
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            StateValue cached = objectMapper.readValue(value, StateValue.class);
            if (cached.schemaVersion() != SCHEMA_VERSION
                    || !username.equals(cached.username())
                    || !workflowId.equals(cached.workflowId())
                    || cached.snapshot() == null
                    || !workflowId.equals(cached.snapshot().workflowId())
                    || isExpired(cached.snapshot())) {
                return Optional.empty();
            }
            return Optional.of(cached.snapshot());
        } catch (JsonProcessingException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    Optional<Snapshot> get(String workflowId) {
        return get(DEMO_USERNAME, workflowId);
    }

    public Optional<Snapshot> findByConversation(String username, String conversationId) {
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis == null || invalid(username) || invalid(conversationId)) {
            return Optional.empty();
        }
        try {
            String workflowId = redis.opsForValue().get(conversationIndexKey(username, conversationId));
            return workflowId == null || workflowId.isBlank()
                    ? Optional.empty()
                    : get(username, workflowId);
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    Optional<Snapshot> findByConversation(String conversationId) {
        return findByConversation(DEMO_USERNAME, conversationId);
    }

    public Optional<List<Slot>> getActiveSlots(String username, String workflowId) {
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis == null || invalid(username) || invalid(workflowId)) {
            return Optional.empty();
        }
        try {
            String value = redis.opsForValue().get(slotsKey(username, workflowId));
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            SlotsValue cached = objectMapper.readValue(value, SlotsValue.class);
            if (cached.schemaVersion() != SCHEMA_VERSION
                    || !username.equals(cached.username())
                    || !workflowId.equals(cached.workflowId())) {
                return Optional.empty();
            }
            return Optional.of(cached.slots() == null ? List.of() : List.copyOf(cached.slots()));
        } catch (JsonProcessingException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    Optional<List<Slot>> getActiveSlots(String workflowId) {
        return getActiveSlots(DEMO_USERNAME, workflowId);
    }

    public Optional<Confirmation> getPendingConfirmation(String username, String workflowId) {
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis == null || invalid(username) || invalid(workflowId)) {
            return Optional.empty();
        }
        try {
            String value = redis.opsForValue().get(confirmationKey(username, workflowId));
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            ConfirmationValue cached = objectMapper.readValue(value, ConfirmationValue.class);
            if (cached.schemaVersion() != SCHEMA_VERSION
                    || !username.equals(cached.username())
                    || !workflowId.equals(cached.workflowId())
                    || cached.confirmation() == null
                    || !"PENDING".equals(cached.confirmation().status())
                    || !cached.confirmation().expiresAt().isAfter(clock.instant())) {
                return Optional.empty();
            }
            return Optional.of(cached.confirmation());
        } catch (JsonProcessingException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    Optional<Confirmation> getPendingConfirmation(String workflowId) {
        return getPendingConfirmation(DEMO_USERNAME, workflowId);
    }

    private Duration ttl(Snapshot snapshot) {
        Confirmation confirmation = snapshot.confirmation();
        if (confirmation == null || confirmation.expiresAt() == null) {
            return DEFAULT_TTL;
        }
        Duration remaining = Duration.between(clock.instant(), confirmation.expiresAt());
        return remaining.isNegative() || remaining.isZero() ? MIN_TTL : remaining;
    }

    private boolean isExpired(Snapshot snapshot) {
        Confirmation confirmation = snapshot.confirmation();
        return confirmation != null
                && "PENDING".equals(confirmation.status())
                && confirmation.expiresAt() != null
                && !confirmation.expiresAt().isAfter(clock.instant());
    }

    private String stateKey(String username, String workflowId) {
        return "roadmind:workflow:" + digest(username) + ':' + workflowId + ":state";
    }

    private String slotsKey(String username, String workflowId) {
        return "roadmind:workflow:" + digest(username) + ':' + workflowId + ":slots";
    }

    private String confirmationKey(String username, String workflowId) {
        return "roadmind:workflow:" + digest(username) + ':' + workflowId + ":confirmation";
    }

    private String conversationIndexKey(String username, String conversationId) {
        return "roadmind:conversation:" + digest(username) + ':' + conversationId + ":workflow";
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private boolean invalid(String value) {
        return value == null || value.isBlank();
    }

    private record StateValue(
            int schemaVersion,
            String username,
            String workflowId,
            Snapshot snapshot) {
    }

    private record SlotsValue(
            int schemaVersion,
            String username,
            String workflowId,
            int contextVersion,
            List<Slot> slots) {
    }

    private record ConfirmationValue(
            int schemaVersion,
            String username,
            String workflowId,
            Confirmation confirmation) {
    }
}
