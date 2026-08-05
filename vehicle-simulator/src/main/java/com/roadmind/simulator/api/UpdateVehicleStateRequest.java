package com.roadmind.simulator.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

public record UpdateVehicleStateRequest(
        @NotNull(message = "expectedVersion 不能为空")
        @DecimalMin(value = "1", message = "expectedVersion 必须大于 0")
        Long expectedVersion,

        @DecimalMin(value = "0.0", message = "batteryPercent 不能小于 0")
        @DecimalMax(value = "100.0", message = "batteryPercent 不能大于 100")
        Double batteryPercent,

        @DecimalMin(value = "-30.0", message = "cabinTemperature 不能低于 -30°C")
        @DecimalMax(value = "60.0", message = "cabinTemperature 不能高于 60°C")
        Double cabinTemperature) {

    @AssertTrue(message = "至少提供一个允许修改的车辆状态字段")
    @JsonIgnore
    public boolean isAnyFieldPresent() {
        return batteryPercent != null || cabinTemperature != null;
    }
}
