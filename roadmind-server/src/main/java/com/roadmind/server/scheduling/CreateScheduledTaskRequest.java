package com.roadmind.server.scheduling;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record CreateScheduledTaskRequest(
        @NotBlank @Size(max = 200) String message,
        @NotNull @Future Instant executeAt,
        @NotBlank @Size(max = 64) String timezone) {
}
