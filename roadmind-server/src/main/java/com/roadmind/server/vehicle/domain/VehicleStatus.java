package com.roadmind.server.vehicle.domain;

import java.time.Instant;

public record VehicleStatus(
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
