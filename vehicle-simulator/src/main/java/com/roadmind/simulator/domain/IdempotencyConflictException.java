package com.roadmind.simulator.domain;

public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException() {
        super("相同 Idempotency-Key 已用于不同参数");
    }
}
