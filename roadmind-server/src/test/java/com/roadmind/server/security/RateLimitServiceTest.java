package com.roadmind.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

class RateLimitServiceTest {

    private ObjectProvider<StringRedisTemplate> provider;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        provider = org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(null);
    }

    @Test
    void localFallbackRejectsAfterFixedWindowLimit() {
        RateLimitService service = new RateLimitService(provider);

        assertThat(service.tryAcquire("messages", "user-1", 2, Duration.ofMinutes(1))).isTrue();
        assertThat(service.tryAcquire("messages", "user-1", 2, Duration.ofMinutes(1))).isTrue();
        assertThat(service.tryAcquire("messages", "user-1", 2, Duration.ofMinutes(1))).isFalse();
        assertThat(service.tryAcquire("messages", "user-2", 2, Duration.ofMinutes(1))).isTrue();
    }

    @Test
    void localFallbackFailsClosedAtCapacityAndReclaimsExpiredWindows() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-05T00:00:00Z"));
        RateLimitService service = new RateLimitService(provider, clock, 2);
        Duration window = Duration.ofMinutes(1);

        assertThat(service.tryAcquire("messages", "user-1", 2, window)).isTrue();
        assertThat(service.tryAcquire("messages", "user-2", 2, window)).isTrue();
        assertThat(service.localWindowCount()).isEqualTo(2);

        assertThat(service.tryAcquire("messages", "user-3", 2, window)).isFalse();
        assertThat(service.localWindowCount()).isEqualTo(2);
        assertThat(service.tryAcquire("messages", "user-1", 2, window)).isTrue();

        clock.advance(window);
        assertThat(service.tryAcquire("messages", "user-3", 2, window)).isTrue();
        assertThat(service.localWindowCount()).isEqualTo(1);
    }

    @Test
    void shortWindowTrafficCannotExpireAnIndependentLongWindow() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-05T00:00:00Z"));
        RateLimitService service = new RateLimitService(provider, clock, 10);

        assertThat(service.tryAcquire("confirmations", "alice", 1, Duration.ofMinutes(5))).isTrue();
        clock.advance(Duration.ofSeconds(2));

        assertThat(service.tryAcquire("sse", "bob", 1, Duration.ofSeconds(1))).isTrue();
        assertThat(service.tryAcquire("confirmations", "alice", 1, Duration.ofMinutes(5))).isFalse();

        clock.advance(Duration.ofMinutes(5));
        assertThat(service.tryAcquire("confirmations", "alice", 1, Duration.ofMinutes(5))).isTrue();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
