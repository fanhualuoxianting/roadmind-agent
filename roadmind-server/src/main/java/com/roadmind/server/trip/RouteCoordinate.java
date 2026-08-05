package com.roadmind.server.trip;

public record RouteCoordinate(double longitude, double latitude, String coordinateSystem) {
    public RouteCoordinate {
        if (!Double.isFinite(longitude) || longitude < -180 || longitude > 180
                || !Double.isFinite(latitude) || latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("路线包含越界坐标");
        }
        if (!"GCJ-02".equals(coordinateSystem)) {
            throw new IllegalArgumentException("RoadMind 中国路线只接受 GCJ-02 坐标");
        }
    }
}
