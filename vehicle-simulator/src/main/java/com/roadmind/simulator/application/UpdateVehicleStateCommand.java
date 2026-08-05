package com.roadmind.simulator.application;

public record UpdateVehicleStateCommand(
        long expectedVersion,
        Double batteryPercent,
        Double cabinTemperature) {

    String fingerprint() {
        return expectedVersion + "|" + normalize(batteryPercent) + "|" + normalize(cabinTemperature);
    }

    private static String normalize(Double value) {
        return value == null ? "-" : java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
