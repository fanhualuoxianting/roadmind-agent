package com.roadmind.simulator.api;

import com.roadmind.simulator.shared.RequestTrace;
import java.time.Instant;

public record SimulatorApiResponse<T>(
        String code,
        String message,
        T data,
        String traceId,
        Instant timestamp) {

    public static <T> SimulatorApiResponse<T> ok(T data) {
        return new SimulatorApiResponse<>("OK", "success", data, RequestTrace.current(), Instant.now());
    }
}
