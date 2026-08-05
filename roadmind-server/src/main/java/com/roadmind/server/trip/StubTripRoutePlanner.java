package com.roadmind.server.trip;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "roadmind.external.route-mode", havingValue = "STUB", matchIfMissing = true)
public class StubTripRoutePlanner implements TripRoutePlanner {
    private final Clock clock = Clock.systemUTC();

    @Override
    public RoutePlan plan(String origin, String destination, boolean avoidTraffic) {
        List<RouteCoordinate> points = List.of(
                point(118.7358, 31.9827), point(118.8226, 32.0191), point(119.0508, 32.0615),
                point(119.2737, 32.1896), point(119.4194, 32.2078), point(119.5750, 32.1164),
                point(119.7302, 31.9631), point(119.9438, 31.7820), point(120.1614, 31.6676),
                point(120.2992, 31.5744));
        return new RoutePlan(
                UUID.randomUUID().toString(), 1, "RoadMind deterministic route fixture", "STUB", "GCJ-02",
                new RoutePlace(origin, points.getFirst().longitude(), points.getFirst().latitude(), "GCJ-02"),
                new RoutePlace(destination, points.getLast().longitude(), points.getLast().latitude(), "GCJ-02"),
                169_000, 8_280, points, RouteHash.of(origin + destination + avoidTraffic, points), clock.instant(), null);
    }

    private RouteCoordinate point(double longitude, double latitude) {
        return new RouteCoordinate(longitude, latitude, "GCJ-02");
    }
}
