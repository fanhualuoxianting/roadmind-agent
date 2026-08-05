package com.roadmind.server.trip;

public class TripNotFoundException extends RuntimeException {
    public TripNotFoundException(String tripId) {
        super("行程不存在: " + tripId);
    }
}
