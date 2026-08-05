package com.roadmind.server.scheduling;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record ScheduledTaskSnapshot(
        long id,
        Long agentTaskId,
        Long planStepId,
        Long confirmationItemId,
        String authorizationWorkflowId,
        String authorizationStepId,
        String authorizationConfirmationId,
        Integer authorizationPlanVersion,
        String authorizationPayloadHash,
        String taskType,
        JsonNode payload,
        String payloadHash,
        String idempotencyKey,
        Instant executeAt,
        String timezone,
        String status,
        int attemptCount,
        String lockedBy,
        Instant lockedUntil,
        String lastErrorCode,
        Instant createdAt,
        Instant updatedAt,
        int version) {
}
