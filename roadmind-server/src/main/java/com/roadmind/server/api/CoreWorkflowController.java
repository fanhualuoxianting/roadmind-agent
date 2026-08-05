package com.roadmind.server.api;

import com.roadmind.server.scheduling.CreateDeferredActionRequest;
import com.roadmind.server.scheduling.ScheduledTaskService;
import com.roadmind.server.scheduling.ScheduledTaskSnapshot;
import com.roadmind.server.workflow.CoreWorkflowModels.ConfirmationRequest;
import com.roadmind.server.workflow.CoreWorkflowModels.DeferredActionAuthorization;
import com.roadmind.server.workflow.CoreWorkflowModels.MessageRequest;
import com.roadmind.server.workflow.CoreWorkflowModels.Snapshot;
import com.roadmind.server.workflow.CoreWorkflowService;
import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class CoreWorkflowController {
    private final CoreWorkflowService service;
    private final ScheduledTaskService scheduledTasks;

    public CoreWorkflowController(CoreWorkflowService service, ScheduledTaskService scheduledTasks) {
        this.service = service;
        this.scheduledTasks = scheduledTasks;
    }

    @PostMapping("/conversations/{conversationId}/workflow-messages")
    ApiResponse<Snapshot> message(
            Principal principal,
            @PathVariable String conversationId,
            @RequestBody MessageRequest request) {
        return ApiResponse.ok(service.message(
                principal.getName(),
                conversationId,
                request.message()));
    }

    @GetMapping("/workflows/{workflowId}")
    ApiResponse<Snapshot> get(Principal principal, @PathVariable String workflowId) {
        return ApiResponse.ok(service.get(principal.getName(), workflowId));
    }

    @PostMapping("/workflows/{workflowId}/confirmation-decisions")
    ApiResponse<Snapshot> decide(
            Principal principal,
            @PathVariable String workflowId,
            @Valid @RequestBody ConfirmationRequest request) {
        return ApiResponse.ok(service.decide(
                principal.getName(),
                workflowId,
                request.decision(),
                request.planVersion(),
                request.payloadHash()));
    }

    @PostMapping("/workflows/{workflowId}/deferred-actions")
    ApiResponse<ScheduledTaskSnapshot> defer(
            Principal principal,
            @PathVariable String workflowId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateDeferredActionRequest request) {
        DeferredActionAuthorization authorization = service.authorizeDeferredAction(
                principal.getName(),
                workflowId,
                request.stepId(),
                request.confirmationId(),
                request.planVersion(),
                request.payloadHash());
        return ApiResponse.ok(scheduledTasks.createAuthorizedHomeControl(
                principal.getName(), authorization, request.executeAt(), request.timezone(), idempotencyKey));
    }
}
