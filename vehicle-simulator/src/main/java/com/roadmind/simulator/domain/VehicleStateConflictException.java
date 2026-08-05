package com.roadmind.simulator.domain;

public class VehicleStateConflictException extends RuntimeException {

    public VehicleStateConflictException(long expectedVersion, long actualVersion) {
        super("车辆状态版本已变化，期望版本 " + expectedVersion + "，当前版本 " + actualVersion);
    }
}
