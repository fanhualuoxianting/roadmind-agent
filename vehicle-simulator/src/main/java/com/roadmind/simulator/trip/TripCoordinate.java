package com.roadmind.simulator.trip;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;

public record TripCoordinate(
        @DecimalMin("-180") @DecimalMax("180") double longitude,
        @DecimalMin("-90") @DecimalMax("90") double latitude,
        @NotBlank String coordinateSystem) {
}
