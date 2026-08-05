package com.roadmind.simulator.domain;

import java.time.Instant;

public record VehicleState(
        String vehicleId,
        String displayName,
        String mode,
        double batteryPercent,
        double estimatedRangeKm,
        double cabinTemperature,
        boolean doorLocked,
        boolean charging,
        String gear,
        GeoLocation location,
        TirePressure tirePressure,
        long stateVersion,
        Instant observedAt) {
}
