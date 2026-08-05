package com.roadmind.server.vehicle.domain;

public record VehicleSummary(
        String vehicleId,
        String displayName,
        String mode,
        String status) {
}
