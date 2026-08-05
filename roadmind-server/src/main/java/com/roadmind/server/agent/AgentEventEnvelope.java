package com.roadmind.server.agent;

import java.time.Instant;
import java.util.Map;

public record AgentEventEnvelope(
        int schemaVersion,
        String eventId,
        long sequence,
        String type,
        String traceId,
        String taskId,
        Instant occurredAt,
        Map<String, Object> data) {
}
