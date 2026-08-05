package com.roadmind.simulator.domain;

public record GeoLocation(
        String coordinateSystem,
        double longitude,
        double latitude,
        String city) {
}
