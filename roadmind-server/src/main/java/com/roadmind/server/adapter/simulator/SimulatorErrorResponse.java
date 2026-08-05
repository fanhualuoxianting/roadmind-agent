package com.roadmind.server.adapter.simulator;

import java.time.Instant;
import java.util.Map;

record SimulatorErrorResponse(
        String code,
        String message,
        Map<String, Object> details,
        String traceId,
        Instant timestamp) {
}
