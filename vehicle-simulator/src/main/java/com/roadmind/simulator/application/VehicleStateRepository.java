package com.roadmind.simulator.application;

import com.roadmind.simulator.domain.VehicleState;

public interface VehicleStateRepository {

    VehicleState get();

    void save(VehicleState state);
}
