package com.roadmind.server.vehicle.domain;

import com.roadmind.server.adapter.simulator.ScheduledClimateCommand;
import java.time.Instant;

public interface VehicleNativeScheduler {

    ScheduledClimateCommand scheduleClimate(
            String simulatorVehicleId,
            Instant executeAt,
            double cabinTemperature,
            String idempotencyKey);
}
