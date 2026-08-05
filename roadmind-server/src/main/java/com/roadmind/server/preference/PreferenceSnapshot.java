package com.roadmind.server.preference;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record PreferenceSnapshot(
        long id,
        String category,
        String preferenceKey,
        JsonNode value,
        String sensitivity,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt,
        int version) {
}
