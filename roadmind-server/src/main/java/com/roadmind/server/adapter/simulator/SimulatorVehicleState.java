package com.roadmind.server.adapter.simulator;

import com.roadmind.server.vehicle.domain.GeoLocation;
import com.roadmind.server.vehicle.domain.TirePressure;
import java.time.Instant;

record SimulatorVehicleState(
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
