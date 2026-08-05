package com.roadmind.server.api;

import com.roadmind.server.shared.RequestTrace;
import java.time.Instant;

public record ApiResponse<T>(
        String code,
        String message,
        T data,
        String traceId,
        Instant timestamp) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>("OK", "success", data, RequestTrace.current(), Instant.now());
    }

    public static <T> ApiResponse<T> of(String code, String message, T data) {
        return new ApiResponse<>(code, message, data, RequestTrace.current(), Instant.now());
    }
}
