package com.roadmind.simulator.trip;

import java.time.Instant;

public record TripSnapshot(
        String tripId,
        String vehicleId,
        TripStatus status,
        long routeVersion,
        String routeHash,
        String sourceMode,
        long sequence,
        TripCoordinate position,
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
