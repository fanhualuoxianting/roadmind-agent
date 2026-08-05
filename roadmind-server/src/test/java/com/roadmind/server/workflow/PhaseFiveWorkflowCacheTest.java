package com.roadmind.server.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.roadmind.server.workflow.CoreWorkflowModels.Snapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
class PhaseFiveWorkflowCacheTest {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:8.10.0"))
            .withExposedPorts(6379);

    private static StringRedisTemplate redisTemplate;
    private static ObjectMapper objectMapper;
    private static CoreWorkflowCache cache;

    @BeforeAll
    static void startRedis() {
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redisTemplate);
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        cache = new CoreWorkflowCache(provider, objectMapper, Clock.systemUTC());
    }

    @BeforeEach
    void clearRedis() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void storesStateSlotsAndPendingConfirmationInSeparateTtlKeys() {
        Snapshot plan = new CoreWorkflowService().message(
                "cache-conversation", "明天早上8点从南京软件谷出发去苏州");
        cache.put(plan);

        assertThat(cache.get(plan.workflowId())).contains(plan);
        assertThat(cache.getActiveSlots(plan.workflowId()).orElseThrow().stream()
                .map(slot -> slot.name()).toList())
                .containsExactly("origin", "destination", "departureTime");
        assertThat(cache.getPendingConfirmation(plan.workflowId())).contains(plan.confirmation());
        assertThat(redisTemplate.hasKey("roadmind:workflow:" + plan.workflowId() + ":state")).isTrue();
        assertThat(redisTemplate.hasKey("roadmind:workflow:" + plan.workflowId() + ":slots")).isTrue();
        assertThat(redisTemplate.hasKey("roadmind:workflow:" + plan.workflowId() + ":confirmation")).isTrue();
        assertThat(cache.findByConversation("cache-conversation")).contains(plan);
    }

    @Test
    void expiredPendingConfirmationIsNotReturnedFromRedis() {
        Instant startedAt = Instant.parse("2026-08-04T00:00:00Z");
        Snapshot plan = new CoreWorkflowService(
                Clock.fixed(startedAt, ZoneOffset.UTC)).message(
                        "cache-expiry", "明天早上8点从南京软件谷出发去苏州");
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redisTemplate);
        CoreWorkflowCache initial = new CoreWorkflowCache(
                provider, objectMapper, Clock.fixed(startedAt, ZoneOffset.UTC));
        initial.put(plan);

        CoreWorkflowCache afterExpiry = new CoreWorkflowCache(
                provider, objectMapper, Clock.fixed(startedAt.plusSeconds(11 * 60L), ZoneOffset.UTC));
        assertThat(afterExpiry.get(plan.workflowId())).isEmpty();
        assertThat(afterExpiry.getPendingConfirmation(plan.workflowId())).isEmpty();
        assertThat(afterExpiry.getActiveSlots(plan.workflowId())).isPresent();
    }

    @Test
    void restoresWorkflowByIdFromRedisWhenMySqlIsUnavailable() {
        CoreWorkflowService first = new CoreWorkflowService(
                Clock.systemUTC(), null, cache);
        Snapshot plan = first.message(
                "cache-recovery", "明天早上8点从南京软件谷出发去苏州");

        CoreWorkflowService afterRestart = new CoreWorkflowService(
                Clock.systemUTC(), null, cache);

        assertThat(afterRestart.get(plan.workflowId())).isEqualTo(plan);
    }
}
