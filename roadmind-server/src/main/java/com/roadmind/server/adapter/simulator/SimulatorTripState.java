package com.roadmind.server.adapter.simulator;

import java.time.Instant;

record SimulatorTripState(
        String tripId,
        String vehicleId,
        String status,
        long routeVersion,
        String routeHash,
        String sourceMode,
        long sequence,
        SimulatorTripCoordinate position,
        double speedKmh,
        double heading,
        double batteryPercent,
        double remainingRangeKm,
        double travelledMeters,
        double remainingDistanceMeters,
        Instant estimatedArrivalTime,
        int simulationSpeed,
        boolean lowBattery,
        Instant observedAt) {
}
