package com.roadmind.server.vehicle.domain;

public record VehicleStateUpdate(
        long expectedVersion,
        Double batteryPercent,
        Double cabinTemperature) {
}
