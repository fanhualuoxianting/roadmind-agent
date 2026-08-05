package com.roadmind.server.api;

import com.roadmind.server.shared.RequestTrace;
import java.time.Instant;
import java.util.Map;

public record ApiErrorResponse(
        String code,
        String message,
        Map<String, Object> details,
        String traceId,
        Instant timestamp) {

    public static ApiErrorResponse of(String code, String message, Map<String, Object> details) {
        return new ApiErrorResponse(code, message, details, RequestTrace.current(), Instant.now());
    }
}
