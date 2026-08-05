package com.roadmind.simulator.api;

import com.roadmind.simulator.shared.RequestTrace;
import java.time.Instant;
import java.util.Map;

public record SimulatorErrorResponse(
        String code,
        String message,
        Map<String, Object> details,
        String traceId,
        Instant timestamp) {

    public static SimulatorErrorResponse of(String code, String message, Map<String, Object> details) {
        return new SimulatorErrorResponse(code, message, details, RequestTrace.current(), Instant.now());
    }
}
