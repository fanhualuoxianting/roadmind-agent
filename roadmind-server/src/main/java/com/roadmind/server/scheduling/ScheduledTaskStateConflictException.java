package com.roadmind.server.scheduling;

public class ScheduledTaskStateConflictException extends RuntimeException {

    public ScheduledTaskStateConflictException(long id, String status) {
        super("定时任务当前状态不可执行此操作: " + id + " / " + status);
    }
}
