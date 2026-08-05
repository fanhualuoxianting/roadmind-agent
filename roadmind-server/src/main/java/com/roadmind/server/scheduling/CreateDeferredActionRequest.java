package com.roadmind.server.scheduling;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record CreateDeferredActionRequest(
        @NotBlank @Size(max = 64) String stepId,
        @NotBlank @Size(max = 64) String confirmationId,
        int planVersion,
        @NotBlank @Size(max = 64) String payloadHash,
        @NotNull @Future Instant executeAt,
        @NotBlank @Size(max = 64) String timezone) {
}
