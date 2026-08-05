package com.roadmind.server.workflow;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class CoreWorkflowModels {
    private CoreWorkflowModels() {}

    public record Slot(String name, String value, String source, int version) {}

    public record Step(
            String stepId,
            String title,
            String toolName,
            List<String> dependsOn,
            String risk,
            String status,
            Map<String, Object> arguments,
            String verification) {}

    public record Confirmation(
            String confirmationId,
            String status,
            int planVersion,
            String payloadHash,
            Instant expiresAt,
            List<String> itemIds) {}

    public record TimelineEvent(Instant occurredAt, String type, String title, String detail) {}

    public record Snapshot(
            String workflowId,
            String conversationId,
            String status,
            int contextVersion,
            int planVersion,
            String prompt,
            List<Slot> slots,
            List<String> missingSlots,
            List<Step> steps,
            Confirmation confirmation,
            List<TimelineEvent> timeline,
            String response,
            Instant updatedAt) {}

    public record DeferredActionAuthorization(
            String workflowId,
            String conversationId,
            String stepId,
            String toolName,
            int planVersion,
            String confirmationId,
            String payloadHash,
            Map<String, Object> arguments) {}

    public record MessageRequest(String message) {}
    public record ConfirmationRequest(String decision, int planVersion, String payloadHash) {}
}
