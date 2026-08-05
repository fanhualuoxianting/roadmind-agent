package com.roadmind.server.vehicle.domain;

public class VehicleNotFoundException extends RuntimeException {

    public VehicleNotFoundException(String vehicleId) {
        super("未找到可访问的数字孪生车辆：" + vehicleId);
    }
}
