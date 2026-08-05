package com.roadmind.server.trip;

import java.time.Instant;
import java.util.List;

public record RoutePlan(
        String routePlanId,
        long routeVersion,
        String provider,
        String sourceMode,
        String coordinateSystem,
        RoutePlace origin,
        RoutePlace destination,
        double distanceMeters,
        long durationSeconds,
        List<RouteCoordinate> polyline,
        String routeHash,
        Instant fetchedAt,
        ChargingStation chargingStation) {

    public RoutePlan withChargingStation(ChargingStation station, String updatedHash) {
        return new RoutePlan(routePlanId, routeVersion + 1, provider, sourceMode, coordinateSystem,
                origin, destination, distanceMeters, durationSeconds + station.chargingMinutes() * 60L,
                polyline, updatedHash, fetchedAt, station);
    }
}
