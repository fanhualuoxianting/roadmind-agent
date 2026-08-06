package com.roadmind.server.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.roadmind.server.agent.AgentResourceNotFoundException;
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
class CoreWorkflowCacheIntegrationTest {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:8.10.0"))
            .withExposedPorts(6379);

    private static StringRedisTemplate redisTemplate;
    private static ObjectMapper objectMapper;
    private static CoreWorkflowCache cache;

    @BeforeAll
    @SuppressWarnings("unchecked")
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
    void storesStateSlotsAndPendingConfirmationInUserScopedTtlKeys() {
        Snapshot plan = new CoreWorkflowService().message(
                "alice",
                "cache-conversation",
                "明天早上8点从南京软件谷出发去苏州");
        cache.put("alice", plan);

        assertThat(cache.get("alice", plan.workflowId())).contains(plan);
        assertThat(cache.get("bob", plan.workflowId())).isEmpty();
        assertThat(cache.getActiveSlots("alice", plan.workflowId()).orElseThrow().stream()
                .map(slot -> slot.name()).toList())
                .containsExactly("origin", "destination", "departureTime");
        assertThat(cache.getActiveSlots("bob", plan.workflowId())).isEmpty();
        assertThat(cache.getPendingConfirmation("alice", plan.workflowId()))
                .contains(plan.confirmation());
        assertThat(cache.getPendingConfirmation("bob", plan.workflowId())).isEmpty();
        assertThat(redisTemplate.keys("roadmind:workflow:*:" + plan.workflowId() + ":state"))
                .hasSize(1);
        assertThat(redisTemplate.keys("roadmind:workflow:*:" + plan.workflowId() + ":slots"))
                .hasSize(1);
        assertThat(redisTemplate.keys("roadmind:workflow:*:" + plan.workflowId() + ":confirmation"))
                .hasSize(1);
        assertThat(cache.findByConversation("alice", "cache-conversation")).contains(plan);
        assertThat(cache.findByConversation("bob", "cache-conversation")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void reKeyedWorkflowPayloadStillRejectsDifferentUser() throws Exception {
        Snapshot plan = new CoreWorkflowService().message(
                "alice",
                "cache-rekey",
                "明天早上8点从南京软件谷出发去苏州");
        cache.put("alice", plan);
        String aliceKey = redisTemplate.keys(
                        "roadmind:workflow:*:" + plan.workflowId() + ":state")
                .iterator()
                .next();
        String aliceJson = redisTemplate.opsForValue().get(aliceKey);

        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redisTemplate);
        CoreWorkflowCache bobOnlyCache = new CoreWorkflowCache(provider, objectMapper, Clock.systemUTC());
        Snapshot bobSeed = new CoreWorkflowService().message(
                "bob",
                "bob-seed",
                "明天早上8点从南京南站出发去无锡");
        bobOnlyCache.put("bob", bobSeed);
        String bobKey = redisTemplate.keys(
                        "roadmind:workflow:*:" + bobSeed.workflowId() + ":state")
                .iterator()
                .next();
        String forgedBobKey = bobKey.replace(bobSeed.workflowId(), plan.workflowId());
        redisTemplate.opsForValue().set(forgedBobKey, aliceJson);

        assertThat(bobOnlyCache.get("bob", plan.workflowId())).isEmpty();
        assertThat(bobOnlyCache.get("alice", plan.workflowId())).contains(plan);
    }

    @Test
    void expiredPendingConfirmationIsNotReturnedFromRedis() {
        Instant startedAt = Instant.parse("2026-08-04T00:00:00Z");
        Snapshot plan = new CoreWorkflowService(
                Clock.fixed(startedAt, ZoneOffset.UTC)).message(
                        "alice",
                        "cache-expiry",
                        "明天早上8点从南京软件谷出发去苏州");
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redisTemplate);
        CoreWorkflowCache initial = new CoreWorkflowCache(
                provider, objectMapper, Clock.fixed(startedAt, ZoneOffset.UTC));
        initial.put("alice", plan);

        CoreWorkflowCache afterExpiry = new CoreWorkflowCache(
                provider,
                objectMapper,
                Clock.fixed(startedAt.plusSeconds(11 * 60L), ZoneOffset.UTC));
        assertThat(afterExpiry.get("alice", plan.workflowId())).isEmpty();
        assertThat(afterExpiry.getPendingConfirmation("alice", plan.workflowId())).isEmpty();
        assertThat(afterExpiry.getActiveSlots("alice", plan.workflowId())).isPresent();
        assertThat(afterExpiry.get("bob", plan.workflowId())).isEmpty();
    }

    @Test
    void restoresWorkflowByIdFromRedisOnlyForItsOwner() {
        CoreWorkflowService first = new CoreWorkflowService(
                Clock.systemUTC(), null, cache);
        Snapshot plan = first.message(
                "alice",
                "cache-recovery",
                "明天早上8点从南京软件谷出发去苏州");

        CoreWorkflowService afterRestart = new CoreWorkflowService(
                Clock.systemUTC(), null, cache);

        assertThat(afterRestart.get("alice", plan.workflowId())).isEqualTo(plan);
        assertThatThrownBy(() -> afterRestart.get("bob", plan.workflowId()))
                .isInstanceOf(AgentResourceNotFoundException.class);
    }
}
