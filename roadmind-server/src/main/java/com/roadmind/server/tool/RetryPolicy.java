package com.roadmind.server.tool;

import java.time.Duration;

public record RetryPolicy(int maxAttempts, Duration backoff) {

    public RetryPolicy {
        if (maxAttempts < 1 || maxAttempts > 2) {
            throw new IllegalArgumentException("阶段 2 工具只允许 1～2 次尝试");
        }
        if (backoff.isNegative()) {
            throw new IllegalArgumentException("backoff 不能为负数");
        }
    }

    public static RetryPolicy none() {
        return new RetryPolicy(1, Duration.ZERO);
    }

    public static RetryPolicy retryOnce(Duration backoff) {
        return new RetryPolicy(2, backoff);
    }
}
