package com.roadmind.server.trip;

import java.time.Instant;
import java.util.Map;

public record TripOutboxEvent(
        long id,
        String aggregateType,
        String aggregateId,
        String eventId,
        long sequence,
        String eventType,
        String traceId,
        Instant occurredAt,
        Map<String, Object> data,
        String status,
        int attemptCount) {
}
