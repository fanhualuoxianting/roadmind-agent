package com.roadmind.server.trip;

import java.time.Instant;
import java.util.Map;

public record TripEventEnvelope(
        int schemaVersion,
        String eventId,
        long sequence,
        String type,
        String traceId,
        String tripId,
        Instant occurredAt,
        Map<String, Object> data) {
}
