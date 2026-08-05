package com.roadmind.simulator.trip;

import jakarta.validation.constraints.NotBlank;

public record TripCommandRequest(@NotBlank String action, Integer simulationSpeed) {
}
