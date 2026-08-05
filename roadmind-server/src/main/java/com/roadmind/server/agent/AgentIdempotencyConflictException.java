package com.roadmind.server.agent;

public class AgentIdempotencyConflictException extends RuntimeException {

    public AgentIdempotencyConflictException() {
        super("相同 Idempotency-Key 已用于不同请求");
    }
}
