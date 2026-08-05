package com.roadmind.server.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** Redis-first fixed-window limiter with a bounded local fallback for dependency outages. */
@Service
public class RateLimitService {

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ConcurrentHashMap<String, LocalWindow> localWindows = new ConcurrentHashMap<>();

    public RateLimitService(ObjectProvider<StringRedisTemplate> redisProvider) {
        this.redisProvider = redisProvider;
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

        long now = System.currentTimeMillis();
        long windowMillis = Math.max(1L, window.toMillis());
        LocalWindow local = localWindows.compute(safeKey, (key, existing) -> {
            if (existing == null || now - existing.startedAt() >= windowMillis) {
                return new LocalWindow(now, new AtomicInteger(1));
            }
            existing.count().incrementAndGet();
            return existing;
        });
        if (localWindows.size() > 10_000) {
            localWindows.entrySet().removeIf(entry -> now - entry.getValue().startedAt() >= windowMillis * 2);
        }
        return local.count().get() <= limit;
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
