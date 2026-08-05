package com.roadmind.server.tool;

import java.time.Instant;

public record ToolExecutionResult(
        boolean success,
        String toolName,
        String toolVersion,
        String executionId,
        Object result,
        String errorCode,
        String errorMessage,
        boolean retryable,
        int attempts,
        Instant startedAt,
        Instant finishedAt,
        long durationMs,
        String traceId) {
}
