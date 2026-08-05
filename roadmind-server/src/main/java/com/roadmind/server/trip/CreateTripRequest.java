package com.roadmind.server.trip;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTripRequest(
        @NotBlank @Size(max = 120) String origin,
        @NotBlank @Size(max = 120) String destination,
        boolean avoidTraffic,
        @DecimalMin("1") @DecimalMax("100") double initialBatteryPercent) {
}
