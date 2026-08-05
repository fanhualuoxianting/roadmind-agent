package com.roadmind.server.adapter.simulator;

record SimulatorUpdateRequest(
        long expectedVersion,
        Double batteryPercent,
        Double cabinTemperature) {
}
