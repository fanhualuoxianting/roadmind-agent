package com.roadmind.server.trip;

public interface TripRoutePlanner {
    RoutePlan plan(String origin, String destination, boolean avoidTraffic);
}
