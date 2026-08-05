package com.roadmind.server.agent;

public record AgentToolCallSnapshot(
        String executionId,
        String toolName,
        String toolVersion,
        String status,
        int attempts,
        long durationMs,
        Object result,
        String errorCode,
        String errorMessage,
        String traceId) {
}
