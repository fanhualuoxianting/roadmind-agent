package com.roadmind.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
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
class RateLimitRedisIntegrationTest {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:8.10.0"))
            .withExposedPorts(6379);

    private static StringRedisTemplate redisTemplate;
    private static RateLimitService service;

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
        service = new RateLimitService(provider);
    }

    @BeforeEach
    void clearRedis() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void incrementAndExpiryAreAppliedByOneAtomicScript() {
        Duration window = Duration.ofSeconds(30);

        assertThat(service.tryAcquire("messages", "user-1", 2, window)).isTrue();
        assertThat(service.tryAcquire("messages", "user-1", 2, window)).isTrue();
        assertThat(service.tryAcquire("messages", "user-1", 2, window)).isFalse();

        Set<String> keys = redisTemplate.keys("roadmind:rate:messages:*");
        assertThat(keys).hasSize(1);
        String key = keys.iterator().next();
        assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("3");
        Long ttlMillis = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);
        assertThat(ttlMillis).isNotNull().isPositive().isLessThanOrEqualTo(window.toMillis());
    }
}
