package com.roadmind.server.vehicle.domain;

public interface VehicleGateway {

    VehicleStatus getStatus(String simulatorVehicleId);

    VehicleStatus updateState(
            String simulatorVehicleId,
            VehicleStateUpdate update,
            String idempotencyKey);
}
