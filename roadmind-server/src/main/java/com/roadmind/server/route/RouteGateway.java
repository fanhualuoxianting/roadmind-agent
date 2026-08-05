package com.roadmind.server.route;

public interface RouteGateway {

    RouteSummary plan(String origin, String destination, boolean avoidTraffic);
}
