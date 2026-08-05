package com.roadmind.server.trip;

public record ChargingStation(String name, RouteCoordinate position, int chargingMinutes) {
}
