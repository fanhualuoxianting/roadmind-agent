package com.roadmind.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

class RateLimitServiceTest {

    @Test
    void localFallbackRejectsAfterFixedWindowLimit() {
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        RateLimitService service = new RateLimitService(provider);

        assertThat(service.tryAcquire("messages", "user-1", 2, Duration.ofMinutes(1))).isTrue();
        assertThat(service.tryAcquire("messages", "user-1", 2, Duration.ofMinutes(1))).isTrue();
        assertThat(service.tryAcquire("messages", "user-1", 2, Duration.ofMinutes(1))).isFalse();
        assertThat(service.tryAcquire("messages", "user-2", 2, Duration.ofMinutes(1))).isTrue();
    }
}
