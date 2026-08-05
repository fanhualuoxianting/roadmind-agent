package com.roadmind.server.trip;

public record TripSnapshot(
        String tripId,
        String vehicleId,
        TripStatus status,
        RoutePlan route,
        TripTelemetry telemetry,
        boolean lowBatteryReplanned,
        String sourceDisclaimer,
        String eventsUrl) {
}
