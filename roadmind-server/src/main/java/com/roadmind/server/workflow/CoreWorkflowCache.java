package com.roadmind.server.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roadmind.server.workflow.CoreWorkflowModels.Confirmation;
import com.roadmind.server.workflow.CoreWorkflowModels.Slot;
import com.roadmind.server.workflow.CoreWorkflowModels.Snapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Best-effort Redis projections for the Core Workflow; MySQL remains the source of truth. */
@Component
public class CoreWorkflowCache {

    private static final int SCHEMA_VERSION = 1;
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);
    private static final Duration MIN_TTL = Duration.ofSeconds(1);

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

    public void put(Snapshot snapshot) {
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis == null) {
            return;
        }
        try {
            Duration stateTtl = ttl(snapshot);
            redis.opsForValue().set(
                    stateKey(snapshot.workflowId()),
                    objectMapper.writeValueAsString(new StateValue(SCHEMA_VERSION, snapshot)),
                    stateTtl);
            redis.opsForValue().set(
                    slotsKey(snapshot.workflowId()),
                    objectMapper.writeValueAsString(new SlotsValue(
                            SCHEMA_VERSION, snapshot.workflowId(), snapshot.contextVersion(), snapshot.slots())),
                    DEFAULT_TTL);

            Confirmation confirmation = snapshot.confirmation();
            if (confirmation != null && "PENDING".equals(confirmation.status())) {
                redis.opsForValue().set(
                        confirmationKey(snapshot.workflowId()),
                        objectMapper.writeValueAsString(new ConfirmationValue(
                                SCHEMA_VERSION, snapshot.workflowId(), confirmation)),
                        ttl(snapshot));
            } else {
                redis.delete(confirmationKey(snapshot.workflowId()));
            }
            redis.opsForValue().set(
                    conversationIndexKey(snapshot.conversationId()),
                    snapshot.workflowId(),
                    DEFAULT_TTL);
        } catch (JsonProcessingException | RuntimeException ignored) {
            // Redis is an optimization; the MySQL snapshot has already been written.
        }
    }

    public Optional<Snapshot> get(String workflowId) {
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis == null) {
            return Optional.empty();
        }
        try {
            String value = redis.opsForValue().get(stateKey(workflowId));
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            StateValue cached = objectMapper.readValue(value, StateValue.class);
            if (cached.schemaVersion() != SCHEMA_VERSION
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

    public Optional<Snapshot> findByConversation(String conversationId) {
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis == null) {
            return Optional.empty();
        }
        try {
            String workflowId = redis.opsForValue().get(conversationIndexKey(conversationId));
            return workflowId == null || workflowId.isBlank() ? Optional.empty() : get(workflowId);
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    public Optional<List<Slot>> getActiveSlots(String workflowId) {
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis == null) {
            return Optional.empty();
        }
        try {
            String value = redis.opsForValue().get(slotsKey(workflowId));
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            SlotsValue cached = objectMapper.readValue(value, SlotsValue.class);
            if (cached.schemaVersion() != SCHEMA_VERSION
                    || !workflowId.equals(cached.workflowId())) {
                return Optional.empty();
            }
            return Optional.of(cached.slots() == null ? List.of() : List.copyOf(cached.slots()));
        } catch (JsonProcessingException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    public Optional<Confirmation> getPendingConfirmation(String workflowId) {
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis == null) {
            return Optional.empty();
        }
        try {
            String value = redis.opsForValue().get(confirmationKey(workflowId));
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            ConfirmationValue cached = objectMapper.readValue(value, ConfirmationValue.class);
            if (cached.schemaVersion() != SCHEMA_VERSION
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

    private String stateKey(String workflowId) {
        return "roadmind:workflow:" + workflowId + ":state";
    }

    private String slotsKey(String workflowId) {
        return "roadmind:workflow:" + workflowId + ":slots";
    }

    private String confirmationKey(String workflowId) {
        return "roadmind:workflow:" + workflowId + ":confirmation";
    }

    private String conversationIndexKey(String conversationId) {
        return "roadmind:conversation:" + conversationId + ":workflow";
    }

    private record StateValue(int schemaVersion, Snapshot snapshot) {
    }

    private record SlotsValue(
            int schemaVersion,
            String workflowId,
            int contextVersion,
            List<Slot> slots) {
    }

    private record ConfirmationValue(
            int schemaVersion,
            String workflowId,
            Confirmation confirmation) {
    }
}
