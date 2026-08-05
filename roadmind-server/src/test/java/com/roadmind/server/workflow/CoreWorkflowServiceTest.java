package com.roadmind.server.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.roadmind.server.agent.AgentResourceNotFoundException;
import com.roadmind.server.home.HomeDeviceService;
import com.roadmind.server.workflow.CoreWorkflowModels.Snapshot;
import org.junit.jupiter.api.Test;

class CoreWorkflowServiceTest {
    private final CoreWorkflowService service = new CoreWorkflowService();

    @Test
    void asksForMissingOriginAndTimeThenCreatesVersionedPlan() {
        Snapshot first = service.message("c1", "明天去苏州");
        assertThat(first.status()).isEqualTo("WAITING_INPUT");
        assertThat(first.missingSlots()).containsExactly("origin", "departureTime");

        Snapshot second = service.message("c1", "明天早上8点从南京软件谷出发去苏州");
        assertThat(second.status()).isEqualTo("WAITING_CONFIRMATION");
        assertThat(second.contextVersion()).isEqualTo(2);
        assertThat(second.planVersion()).isEqualTo(1);
        assertThat(second.steps()).hasSize(5);
        assertThat(second.confirmation().payloadHash()).hasSize(64);
    }

    @Test
    void explicitCorrectionOverwritesOriginAndIncrementsPlanVersion() {
        Snapshot first = service.message("c2", "明天早上8点从南京软件谷出发去苏州");
        Snapshot second = service.message("c2", "不是学校，从南京南站出发去苏州，明天早上9点");
        assertThat(second.planVersion()).isEqualTo(first.planVersion() + 1);
        assertThat(second.slots()).filteredOn(slot -> slot.name().equals("origin"))
                .extracting(slot -> slot.value()).containsExactly("南京南站");
    }

    @Test
    void confirmationIsBoundToPlanAndIdempotent() {
        Snapshot plan = service.message("c3", "明天早上8点从南京软件谷出发去苏州");
        Snapshot approved = service.decide(
                plan.workflowId(),
                "APPROVE",
                plan.planVersion(),
                plan.confirmation().payloadHash());
        assertThat(approved.status()).isEqualTo("SUCCEEDED");
        Snapshot replay = service.decide(
                plan.workflowId(),
                "APPROVE",
                plan.planVersion(),
                plan.confirmation().payloadHash());
        assertThat(replay.timeline()).hasSameSizeAs(approved.timeline());
        Snapshot other = service.message("c3b", "明天早上8点从南京软件谷出发去苏州");
        assertThatThrownBy(() -> service.decide(other.workflowId(), "APPROVE", 99, "bad"))
                .isInstanceOf(WorkflowConflictException.class);
    }

    @Test
    void verifierMismatchProducesPartialSuccess() {
        Snapshot plan = service.message("c4", "明天早上8点从南京软件谷出发去苏州，演示验证失败");
        Snapshot result = service.decide(
                plan.workflowId(),
                "APPROVE",
                plan.planVersion(),
                plan.confirmation().payloadHash());
        assertThat(result.status()).isEqualTo("PARTIAL_SUCCESS");
        assertThat(result.steps()).anyMatch(step -> step.status().equals("VERIFICATION_FAILED"));
    }

    @Test
    void deferredApprovalKeepsOriginalHighRiskAuthorizationBoundToStep() {
        CoreWorkflowService deferredService = new CoreWorkflowService(
                java.time.Clock.systemUTC(), null, null, null, new HomeDeviceService());
        Snapshot plan = deferredService.message(
                "c5", "明天早上8点从南京软件谷出发去苏州");

        Snapshot waiting = deferredService.decide(
                plan.workflowId(),
                "APPROVE_DEFERRED",
                plan.planVersion(),
                plan.confirmation().payloadHash());
        assertThat(waiting.status()).isEqualTo("WAITING_SCHEDULE");
        assertThat(waiting.steps()).filteredOn(step -> step.stepId().equals("s5"))
                .extracting(step -> step.status()).containsExactly("DEFERRED");

        var authorization = deferredService.authorizeDeferredAction(
                plan.workflowId(),
                "s5",
                plan.confirmation().confirmationId(),
                plan.planVersion(),
                plan.confirmation().payloadHash());
        assertThat(authorization.toolName()).isEqualTo("home.set_light");
        assertThat(authorization.payloadHash()).isEqualTo(plan.confirmation().payloadHash());
        assertThatThrownBy(() -> deferredService.authorizeDeferredAction(
                plan.workflowId(),
                "s5",
                plan.confirmation().confirmationId(),
                plan.planVersion(),
                "wrong"))
                .isInstanceOf(WorkflowConflictException.class);
    }

    @Test
    void sameConversationIdAndWorkflowIdCannotCrossUserBoundary() {
        CoreWorkflowService ownedService = new CoreWorkflowService(
                java.time.Clock.systemUTC(), null, null, null, new HomeDeviceService());
        String conversationId = "shared-browser-visible-id";
        Snapshot alicePlan = ownedService.message(
                "alice",
                conversationId,
                "明天早上8点从南京软件谷出发去苏州");
        Snapshot bobPlan = ownedService.message(
                "bob",
                conversationId,
                "明天早上9点从南京南站出发去无锡");

        assertThat(bobPlan.workflowId()).isNotEqualTo(alicePlan.workflowId());
        assertThat(ownedService.get("alice", alicePlan.workflowId())).isEqualTo(alicePlan);
        assertThat(ownedService.get("bob", bobPlan.workflowId())).isEqualTo(bobPlan);

        assertThatThrownBy(() -> ownedService.get("bob", alicePlan.workflowId()))
                .isInstanceOf(AgentResourceNotFoundException.class);
        assertThatThrownBy(() -> ownedService.decide(
                "bob",
                alicePlan.workflowId(),
                "APPROVE",
                alicePlan.planVersion(),
                alicePlan.confirmation().payloadHash()))
                .isInstanceOf(AgentResourceNotFoundException.class);

        Snapshot aliceDeferred = ownedService.decide(
                "alice",
                alicePlan.workflowId(),
                "APPROVE_DEFERRED",
                alicePlan.planVersion(),
                alicePlan.confirmation().payloadHash());
        assertThat(aliceDeferred.status()).isEqualTo("WAITING_SCHEDULE");
        assertThatThrownBy(() -> ownedService.authorizeDeferredAction(
                "bob",
                alicePlan.workflowId(),
                "s5",
                alicePlan.confirmation().confirmationId(),
                alicePlan.planVersion(),
                alicePlan.confirmation().payloadHash()))
                .isInstanceOf(AgentResourceNotFoundException.class);
    }
}
