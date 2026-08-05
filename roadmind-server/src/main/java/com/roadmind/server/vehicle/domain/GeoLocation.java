package com.roadmind.server.vehicle.domain;

public record GeoLocation(
        String coordinateSystem,
        double longitude,
        double latitude,
        String city) {
}
