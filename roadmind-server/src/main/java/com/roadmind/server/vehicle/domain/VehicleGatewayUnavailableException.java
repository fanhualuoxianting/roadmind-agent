package com.roadmind.server.vehicle.domain;

public class VehicleGatewayUnavailableException extends RuntimeException {

    public VehicleGatewayUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
