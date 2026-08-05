package com.roadmind.server.scheduling;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(ScheduledTaskService.class)
@ConditionalOnProperty(
        prefix = "roadmind.scheduling",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
class ScheduledTaskWorker {

    private final ScheduledTaskService service;

    ScheduledTaskWorker(ScheduledTaskService service) {
        this.service = service;
    }

    @Scheduled(
            fixedDelayString = "${roadmind.scheduling.poll-interval:1000ms}",
            initialDelayString = "${roadmind.scheduling.initial-delay:1000ms}")
    void poll() {
        service.pollDueTasks();
    }
}
