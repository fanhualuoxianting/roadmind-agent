package com.roadmind.simulator.application;

import java.time.Instant;

public record ScheduledClimateCommand(
        String commandId,
        String vehicleId,
        Instant executeAt,
        double cabinTemperature,
        String status,
        String idempotencyKey,
        Instant createdAt,
        Instant executedAt,
        Long resultingStateVersion) {
}
