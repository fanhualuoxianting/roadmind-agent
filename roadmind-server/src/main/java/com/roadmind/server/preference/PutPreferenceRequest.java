package com.roadmind.server.preference;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record PutPreferenceRequest(
        @NotNull JsonNode value,
        @Size(max = 24) String sensitivity,
        Instant expiresAt) {
}
