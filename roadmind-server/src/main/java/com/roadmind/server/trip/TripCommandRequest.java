package com.roadmind.server.trip;

import jakarta.validation.constraints.NotBlank;

public record TripCommandRequest(@NotBlank String action, Integer simulationSpeed) {
}
