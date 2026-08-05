package com.roadmind.server.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AgentRequestPayload(
        @NotBlank @Size(max = 8192) String message,
        @Valid ClientContext clientContext) {

    public record ClientContext(@Size(max = 64) String timezone) {
    }
}
