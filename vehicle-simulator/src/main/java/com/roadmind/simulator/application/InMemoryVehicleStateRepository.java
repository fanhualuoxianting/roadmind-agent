package com.roadmind.simulator.application;

import com.roadmind.simulator.domain.VehicleState;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryVehicleStateRepository implements VehicleStateRepository {

    private final AtomicReference<VehicleState> state = new AtomicReference<>();

    @Override
    public VehicleState get() {
        return state.get();
    }

    @Override
    public void save(VehicleState nextState) {
        state.set(nextState);
    }
}
