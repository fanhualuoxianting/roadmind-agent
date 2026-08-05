package com.roadmind.server.scheduling;

public class ScheduledTaskNotFoundException extends RuntimeException {

    public ScheduledTaskNotFoundException(long id) {
        super("定时任务不存在: " + id);
    }
}
