package com.roadmind.server.vehicle.domain;

import org.springframework.http.HttpStatusCode;

public class VehicleGatewayClientException extends RuntimeException {

    private final HttpStatusCode status;
    private final String code;

    public VehicleGatewayClientException(HttpStatusCode status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatusCode status() {
        return status;
    }

    public String code() {
        return code;
    }
}
