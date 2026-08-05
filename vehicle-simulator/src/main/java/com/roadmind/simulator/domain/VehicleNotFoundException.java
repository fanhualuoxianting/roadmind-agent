package com.roadmind.simulator.domain;

public class VehicleNotFoundException extends RuntimeException {

    public VehicleNotFoundException(String vehicleId) {
        super("未找到数字孪生车辆：" + vehicleId);
    }
}
