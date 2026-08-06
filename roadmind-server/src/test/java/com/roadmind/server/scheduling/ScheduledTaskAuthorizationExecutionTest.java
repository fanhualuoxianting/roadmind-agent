package com.roadmind.server.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.roadmind.server.home.HomeDeviceService;
import com.roadmind.server.workflow.CoreWorkflowModels.DeferredActionAuthorization;
import com.roadmind.server.workflow.CoreWorkflowService;
import com.roadmind.server.workflow.DeferredWorkflowAuthorizationValidator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;

class ScheduledTaskAuthorizationExecutionTest {

    private ScheduledTaskRepository repository;
    private HomeDeviceService homeDevices;
    private CoreWorkflowService workflowService;
    private DeferredWorkflowAuthorizationValidator validator;
    private ScheduledTaskService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(ScheduledTaskRepository.class);
        homeDevices = mock(HomeDeviceService.class);
        workflowService = mock(CoreWorkflowService.class);
        validator = mock(DeferredWorkflowAuthorizationValidator.class);
        ObjectProvider<ScheduledTaskRepository> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(repository);
        when(repository.isAvailable()).thenReturn(true);
        service = new ScheduledTaskService(
                provider,
                new ObjectMapper(),
                homeDevices,
                workflowService,
                validator);
    }

    @Test
    void validatesAuthorizationAndPayloadBeforeHomeSideEffect() {
        Instant now = Instant.parse("2026-08-05T00:00:00Z");
        ScheduledTaskSnapshot task = homeTask(now, "demo-home-light-01", false);
        DeferredActionAuthorization authorization = authorization("demo-home-light-01", false);
        when(repository.claimDueTasks("worker-1", now, now.plusSeconds(30), 20))
                .thenReturn(List.of(task));
        when(repository.markRunning(task.id(), "worker-1", now)).thenReturn(true);
        when(validator.validate(
                task.authorizationWorkflowId(),
                task.authorizationStepId(),
                task.authorizationConfirmationId(),
                task.authorizationPlanVersion(),
                task.authorizationPayloadHash()))
                .thenReturn(authorization);

        assertThat(service.pollDueTasks("worker-1", now)).containsExactly(task);

        InOrder order = inOrder(validator, homeDevices, workflowService);
        order.verify(validator).validate(
                task.authorizationWorkflowId(),
                task.authorizationStepId(),
                task.authorizationConfirmationId(),
                task.authorizationPlanVersion(),
                task.authorizationPayloadHash());
        order.verify(homeDevices).setLight(
                "demo-home-light-01",
                false,
                "workflow-1/confirmation-1");
        order.verify(workflowService).markDeferredActionSucceededAuthorized(
                "workflow-1",
                "s5",
                "confirmation-1",
                3,
                "f".repeat(64),
                "501");
        verify(repository).markSucceeded(eq(task.id()), eq("worker-1"), any(Instant.class));
        verify(repository, never()).markFailed(
                eq(task.id()), eq("worker-1"), anyString(), any(Instant.class));
    }

    @Test
    void tamperedPayloadFailsBeforeAnyHomeWrite() {
        Instant now = Instant.parse("2026-08-05T00:00:00Z");
        ScheduledTaskSnapshot task = homeTask(now, "other-light", true);
        when(repository.claimDueTasks("worker-2", now, now.plusSeconds(30), 20))
                .thenReturn(List.of(task));
        when(repository.markRunning(task.id(), "worker-2", now)).thenReturn(true);
        when(validator.validate(
                task.authorizationWorkflowId(),
                task.authorizationStepId(),
                task.authorizationConfirmationId(),
                task.authorizationPlanVersion(),
                task.authorizationPayloadHash()))
                .thenReturn(authorization("demo-home-light-01", false));

        service.pollDueTasks("worker-2", now);

        verify(homeDevices, never()).setLight(anyString(), anyBoolean(), anyString());
        verify(workflowService, never()).markDeferredActionSucceededAuthorized(
                anyString(), anyString(), anyString(), anyInt(), anyString(), anyString());
        verify(repository).markFailed(
                eq(task.id()),
                eq("worker-2"),
                eq("TASK_EXECUTION_FAILED"),
                any(Instant.class));
        verify(repository, never()).markSucceeded(
                eq(task.id()), eq("worker-2"), any(Instant.class));
    }

    private ScheduledTaskSnapshot homeTask(Instant now, String deviceId, boolean on) {
        ObjectNode payload = new ObjectMapper().createObjectNode()
                .put("kind", "HOME_CONTROL")
                .put("deviceId", deviceId)
                .put("on", on);
        return new ScheduledTaskSnapshot(
                501L,
                null,
                null,
                null,
                "workflow-1",
                "s5",
                "confirmation-1",
                3,
                "f".repeat(64),
                "HOME_CONTROL",
                payload,
                "payload-hash",
                "idempotency-key",
                now.minusSeconds(1),
                "Asia/Shanghai",
                "CLAIMED",
                1,
                "worker",
                now.plusSeconds(30),
                null,
                now.minusSeconds(60),
                now,
                0);
    }

    private DeferredActionAuthorization authorization(String deviceId, boolean on) {
        return new DeferredActionAuthorization(
                "workflow-1",
                "conversation-1",
                "s5",
                "home.set_light",
                3,
                "confirmation-1",
                "f".repeat(64),
                Map.of("deviceId", deviceId, "on", on));
    }
}
