package com.roadmind.server.workflow;

import com.roadmind.server.agent.AgentResourceNotFoundException;
import com.roadmind.server.workflow.CoreWorkflowModels.Confirmation;
import com.roadmind.server.workflow.CoreWorkflowModels.DeferredActionAuthorization;
import com.roadmind.server.workflow.CoreWorkflowModels.Snapshot;
import com.roadmind.server.workflow.CoreWorkflowModels.Step;
import org.springframework.stereotype.Component;

/**
 * Revalidates the durable workflow snapshot before a scheduled worker performs a deferred
 * high-risk side effect. Scheduled tasks are MySQL-backed, so the durable snapshot is the
 * authoritative precondition for execution.
 */
@Component
public class DeferredWorkflowAuthorizationValidator {

    private final CoreWorkflowPersistence persistence;

    public DeferredWorkflowAuthorizationValidator(CoreWorkflowPersistence persistence) {
        this.persistence = persistence;
    }

    public DeferredActionAuthorization validate(
            String workflowId,
            String stepId,
            String confirmationId,
            int planVersion,
            String payloadHash) {
        CoreWorkflowPersistence.OwnedSnapshot owned = persistence.findOwnedById(workflowId)
                .orElseThrow(() -> new AgentResourceNotFoundException("工作流", workflowId));
        Snapshot snapshot = owned.snapshot();
        Confirmation confirmation = snapshot.confirmation();
        if (confirmation == null || !"APPROVED".equals(confirmation.status())) {
            throw new WorkflowConflictException("高风险动作尚未完成用户确认");
        }
        if (!confirmation.confirmationId().equals(confirmationId)
                || confirmation.planVersion() != planVersion
                || !confirmation.payloadHash().equals(payloadHash)
                || snapshot.planVersion() != planVersion) {
            throw new WorkflowConflictException("延后动作授权摘要已变化，请重新确认");
        }
        Step step = snapshot.steps().stream()
                .filter(candidate -> candidate.stepId().equals(stepId))
                .findFirst()
                .orElseThrow(() -> new AgentResourceNotFoundException("计划步骤", stepId));
        if (!"HIGH".equals(step.risk())
                || !"DEFERRED".equals(step.status())
                || !"home.set_light".equals(step.toolName())) {
            throw new WorkflowConflictException("该计划步骤不是可执行的已授权延后家居动作");
        }
        return new DeferredActionAuthorization(
                snapshot.workflowId(),
                snapshot.conversationId(),
                step.stepId(),
                step.toolName(),
                snapshot.planVersion(),
                confirmation.confirmationId(),
                confirmation.payloadHash(),
                step.arguments());
    }
}
