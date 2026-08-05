package com.roadmind.server.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.roadmind.server.agent.AgentResourceNotFoundException;
import com.roadmind.server.workflow.CoreWorkflowModels.Confirmation;
import com.roadmind.server.workflow.CoreWorkflowModels.Snapshot;
import com.roadmind.server.workflow.CoreWorkflowModels.Step;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DeferredWorkflowAuthorizationValidatorTest {

    @Test
    void acceptsOnlyMatchingApprovedDeferredHomeStep() {
        CoreWorkflowPersistence persistence = mock(CoreWorkflowPersistence.class);
        Snapshot snapshot = snapshot();
        when(persistence.findOwnedById(snapshot.workflowId()))
                .thenReturn(Optional.of(new CoreWorkflowPersistence.OwnedSnapshot("alice", snapshot)));
        DeferredWorkflowAuthorizationValidator validator =
                new DeferredWorkflowAuthorizationValidator(persistence);

        var authorization = validator.validate(
                snapshot.workflowId(),
                "s5",
                snapshot.confirmation().confirmationId(),
                snapshot.planVersion(),
                snapshot.confirmation().payloadHash());

        assertThat(authorization.toolName()).isEqualTo("home.set_light");
        assertThat(authorization.arguments())
                .containsEntry("deviceId", "demo-home-light-01")
                .containsEntry("on", false);
    }

    @Test
    void rejectsMissingWorkflowAndChangedAuthorization() {
        CoreWorkflowPersistence persistence = mock(CoreWorkflowPersistence.class);
        Snapshot snapshot = snapshot();
        when(persistence.findOwnedById("missing")).thenReturn(Optional.empty());
        when(persistence.findOwnedById(snapshot.workflowId()))
                .thenReturn(Optional.of(new CoreWorkflowPersistence.OwnedSnapshot("alice", snapshot)));
        DeferredWorkflowAuthorizationValidator validator =
                new DeferredWorkflowAuthorizationValidator(persistence);

        assertThatThrownBy(() -> validator.validate(
                "missing", "s5", "confirmation-1", 3, "f".repeat(64)))
                .isInstanceOf(AgentResourceNotFoundException.class);
        assertThatThrownBy(() -> validator.validate(
                snapshot.workflowId(),
                "s5",
                snapshot.confirmation().confirmationId(),
                snapshot.planVersion(),
                "0".repeat(64)))
                .isInstanceOf(WorkflowConflictException.class);
        assertThatThrownBy(() -> validator.validate(
                snapshot.workflowId(),
                "s4",
                snapshot.confirmation().confirmationId(),
                snapshot.planVersion(),
                snapshot.confirmation().payloadHash()))
                .isInstanceOf(WorkflowConflictException.class);
    }

    private Snapshot snapshot() {
        Instant now = Instant.parse("2026-08-05T00:00:00Z");
        Confirmation confirmation = new Confirmation(
                "confirmation-1",
                "APPROVED",
                3,
                "f".repeat(64),
                now.plusSeconds(600),
                List.of("s5"));
        return new Snapshot(
                "workflow-1",
                "conversation-1",
                "WAITING_SCHEDULE",
                2,
                3,
                "明天早上8点出发",
                List.of(),
                List.of(),
                List.of(
                        new Step(
                                "s4",
                                "预约车辆预热",
                                "vehicle.schedule_precondition",
                                List.of(),
                                "HIGH",
                                "SUCCEEDED",
                                Map.of("temperature", 24),
                                "已验证"),
                        new Step(
                                "s5",
                                "关闭家中灯光",
                                "home.set_light",
                                List.of(),
                                "HIGH",
                                "DEFERRED",
                                Map.of("deviceId", "demo-home-light-01", "on", false),
                                "等待执行")),
                confirmation,
                List.of(),
                "等待延后任务",
                now);
    }
}
