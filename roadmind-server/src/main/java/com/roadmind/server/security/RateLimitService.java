package com.roadmind.server.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** Redis-first fixed-window limiter with a strictly bounded local fallback for dependency outages. */
@Service
public class RateLimitService {

    private static final int DEFAULT_MAX_LOCAL_WINDOWS = 10_000;

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ConcurrentHashMap<String, LocalWindow> localWindows = new ConcurrentHashMap<>();
    private final Object localWindowLock = new Object();
    private final Clock clock;
    private final int maxLocalWindows;

    public RateLimitService(ObjectProvider<StringRedisTemplate> redisProvider) {
        this(redisProvider, Clock.systemUTC(), DEFAULT_MAX_LOCAL_WINDOWS);
    }

    RateLimitService(
            ObjectProvider<StringRedisTemplate> redisProvider,
            Clock clock,
            int maxLocalWindows) {
        if (maxLocalWindows < 1) {
            throw new IllegalArgumentException("maxLocalWindows must be positive");
        }
        this.redisProvider = redisProvider;
        this.clock = clock;
        this.maxLocalWindows = maxLocalWindows;
    }

    public boolean tryAcquire(String bucket, String subject, int limit, Duration window) {
        if (limit <= 0) return true;
        String safeKey = "roadmind:rate:" + bucket + ":" + digest(subject == null ? "anonymous" : subject);
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis != null) {
            try {
                Long count = redis.opsForValue().increment(safeKey);
                if (count != null && count == 1L) redis.expire(safeKey, window);
                if (count != null) return count <= limit;
            } catch (RuntimeException ignored) {
                // Redis is a coordination optimization; fall through to a bounded local guard.
            }
        }

        long now = clock.millis();
        long windowMillis = Math.max(1L, window.toMillis());
        synchronized (localWindowLock) {
            LocalWindow existing = localWindows.get(safeKey);
            if (existing != null) {
                if (now - existing.startedAt() < windowMillis) {
                    return existing.count().incrementAndGet() <= limit;
                }
                localWindows.remove(safeKey, existing);
            }

            evictExpired(now, windowMillis);
            if (localWindows.size() >= maxLocalWindows) {
                // Fail closed instead of allowing a Redis outage plus high-cardinality subjects
                // to turn the local fallback into an unbounded memory sink.
                return false;
            }

            localWindows.put(safeKey, new LocalWindow(now, new AtomicInteger(1)));
            return true;
        }
    }

    int localWindowCount() {
        return localWindows.size();
    }

    private void evictExpired(long now, long windowMillis) {
        localWindows.entrySet().removeIf(entry -> now - entry.getValue().startedAt() >= windowMillis);
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private record LocalWindow(long startedAt, AtomicInteger count) {
    }
}
