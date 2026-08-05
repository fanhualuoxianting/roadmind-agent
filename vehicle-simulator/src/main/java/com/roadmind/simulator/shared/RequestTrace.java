package com.roadmind.simulator.shared;

import org.slf4j.MDC;

public final class RequestTrace {

    private RequestTrace() {
    }

    public static String current() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "unavailable" : traceId;
    }
}
