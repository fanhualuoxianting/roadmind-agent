package com.roadmind.simulator.trip;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ConfigureTripRequest(
        @NotBlank String tripId,
        @NotBlank String vehicleId,
        @Positive long routeVersion,
        @NotBlank String routeHash,
        @NotBlank String sourceMode,
        @Size(min = 2, max = 10_000) List<@Valid TripCoordinate> polyline,
        @DecimalMin("1") @DecimalMax("100") double initialBatteryPercent) {
}
