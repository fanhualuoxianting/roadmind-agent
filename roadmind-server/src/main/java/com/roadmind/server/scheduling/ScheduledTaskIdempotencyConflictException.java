package com.roadmind.server.scheduling;

public class ScheduledTaskIdempotencyConflictException extends RuntimeException {

    public ScheduledTaskIdempotencyConflictException() {
        super("相同 Idempotency-Key 已用于不同定时任务");
    }
}
