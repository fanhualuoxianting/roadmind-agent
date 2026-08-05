package com.roadmind.server.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateConversationRequest(
        @NotBlank @Size(max = 100) String title,
        @NotBlank @Size(max = 64) String timezone) {
}
