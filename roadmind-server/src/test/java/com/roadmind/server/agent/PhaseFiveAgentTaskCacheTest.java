package com.roadmind.server.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class PhaseFiveAgentTaskCacheTest {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:8.10.0"))
            .withExposedPorts(6379);

    private static StringRedisTemplate redisTemplate;
    private static AgentTaskCache cache;

    @BeforeAll
    static void startRedis() {
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redisTemplate);
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        cache = new AgentTaskCache(provider, objectMapper);
    }

    @BeforeEach
    void clearRedis() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void storesTaskSnapshotAndIdempotencyProjectionSeparately() {
        Instant now = Instant.parse("2026-08-04T00:00:00Z");
        AgentTaskSnapshot snapshot = new AgentTaskSnapshot(
                "task-cache-1", "conversation-cache-1", "查询车辆", "SUCCEEDED",
                "RULE_STUB", "rule-stub", true, false, "已完成", List.of(), 0, 0, now, now);
        cache.putSnapshot("roadmind-demo", snapshot);
        cache.putIdempotency("roadmind-demo", snapshot.conversationId(), "cache-key-1", "hash-1", snapshot.taskId());

        assertThat(cache.getSnapshot("roadmind-demo", snapshot.taskId())).contains(snapshot);
        assertThat(cache.findIdempotency(
                "roadmind-demo", snapshot.conversationId(), "cache-key-1"))
                .contains(new AgentTaskReplay("hash-1", snapshot));
        assertThat(redisTemplate.hasKey(
                "roadmind:agent-task:roadmind-demo:" + snapshot.taskId() + ":snapshot")).isTrue();
    }

    @Test
    void taskSnapshotKeyAndPayloadAreUserScoped() {
        Instant now = Instant.parse("2026-08-04T00:00:00Z");
        AgentTaskSnapshot snapshot = new AgentTaskSnapshot(
                "task-cache-2", "conversation-cache-2", "查询路线", "SUCCEEDED",
                "RULE_STUB", "rule-stub", true, false, "已完成", List.of(), 0, 0, now, now);
        cache.putSnapshot("alice", snapshot);

        assertThat(cache.getSnapshot("alice", snapshot.taskId())).contains(snapshot);
        assertThat(cache.getSnapshot("bob", snapshot.taskId())).isEmpty();

        String aliceKey = "roadmind:agent-task:alice:" + snapshot.taskId() + ":snapshot";
        String bobKey = "roadmind:agent-task:bob:" + snapshot.taskId() + ":snapshot";
        String encoded = redisTemplate.opsForValue().get(aliceKey);
        assertThat(encoded).isNotNull();
        redisTemplate.opsForValue().set(bobKey, encoded);

        assertThat(cache.getSnapshot("bob", snapshot.taskId())).isEmpty();
    }

    @Test
    void idempotencyPayloadRejectsARekeyedDifferentUser() {
        Instant now = Instant.parse("2026-08-04T00:00:00Z");
        AgentTaskSnapshot snapshot = new AgentTaskSnapshot(
                "task-cache-3", "conversation-cache-3", "查询天气", "SUCCEEDED",
                "RULE_STUB", "rule-stub", true, false, "已完成", List.of(), 0, 0, now, now);
        cache.putSnapshot("alice", snapshot);
        cache.putIdempotency("alice", snapshot.conversationId(), "shared-key", "hash-3", snapshot.taskId());

        String aliceKey = "roadmind:idempotency:agent-task:alice:"
                + snapshot.conversationId() + ":shared-key";
        String bobKey = "roadmind:idempotency:agent-task:bob:"
                + snapshot.conversationId() + ":shared-key";
        String encoded = redisTemplate.opsForValue().get(aliceKey);
        assertThat(encoded).isNotNull();
        redisTemplate.opsForValue().set(bobKey, encoded);

        assertThat(cache.findIdempotency("bob", snapshot.conversationId(), "shared-key")).isEmpty();
    }

    @Test
    void cacheMissDoesNotInventAnAgentTask() {
        assertThat(cache.findIdempotency(
                "roadmind-demo", "missing-conversation", "missing-key")).isEmpty();
        assertThat(cache.getSnapshot("roadmind-demo", "missing-task")).isEmpty();
    }
}
