package com.roadmind.server.trip;

import java.time.Instant;

public record TripTelemetry(
        long sequence,
        RouteCoordinate position,
        double speedKmh,
        double heading,
        double batteryPercent,
        double remainingRangeKm,
        double travelledMeters,
        double remainingDistanceMeters,
        Instant estimatedArrivalTime,
        TripStatus status,
        int simulationSpeed,
        Instant observedAt) {
}
