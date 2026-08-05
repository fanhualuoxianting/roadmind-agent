package com.roadmind.server.adapter.simulator;

import java.time.Instant;

record SimulatorApiResponse<T>(
        String code,
        String message,
        T data,
        String traceId,
        Instant timestamp) {
}
