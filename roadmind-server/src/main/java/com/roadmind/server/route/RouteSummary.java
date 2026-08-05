package com.roadmind.server.route;

import java.time.Instant;

public record RouteSummary(
        String origin,
        String destination,
        double distanceKm,
        long durationMinutes,
        boolean trafficAvoidance,
        String provider,
        String providerRouteId,
        String sourceMode,
        Instant fetchedAt,
        String disclaimer) {
}
