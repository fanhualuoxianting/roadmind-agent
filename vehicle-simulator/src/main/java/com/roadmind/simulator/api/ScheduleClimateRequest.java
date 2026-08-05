package com.roadmind.simulator.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record ScheduleClimateRequest(
        @NotNull @Future Instant executeAt,
        @NotNull @DecimalMin("-30.0") @DecimalMax("60.0") Double cabinTemperature) {
}
