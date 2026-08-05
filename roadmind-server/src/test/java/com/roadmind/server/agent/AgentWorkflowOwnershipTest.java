package com.roadmind.server.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.roadmind.server.audit.AuditService;
import com.roadmind.server.conversation.ConversationContextService;
import com.roadmind.server.preference.PreferenceService;
import com.roadmind.server.tool.ToolRuntime;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class AgentWorkflowOwnershipTest {

    private AgentWorkflowService service;
    private AgentTaskPersistence taskPersistence;
    private AgentTaskCache taskCache;
    private ObjectProvider<PreferenceService> preferenceProvider;
    private ConversationContextService contextService;
    private AgentEventHub eventHub;
    private AgentPlannerRouter planner;
    private ToolRuntime toolRuntime;
    private AuditService audit;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        preferenceProvider = mock(ObjectProvider.class);
        taskPersistence = mock(AgentTaskPersistence.class);
        taskCache = mock(AgentTaskCache.class);
        contextService = mock(ConversationContextService.class);
        eventHub = mock(AgentEventHub.class);
        planner = mock(AgentPlannerRouter.class);
        toolRuntime = mock(ToolRuntime.class);
        audit = mock(AuditService.class);
        when(taskPersistence.findByIdempotencyKey(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(taskPersistence.isAvailable()).thenReturn(false);
        when(taskCache.findIdempotency(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        service = new AgentWorkflowService(
                new InMemoryAgentStore(),
                eventHub,
                planner,
                toolRuntime,
                preferenceProvider,
                contextService,
                taskPersistence,
                taskCache,
                new PromptRiskScanner(),
                audit);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void inMemoryConversationCannotBeUsedByAnotherUser() {
        ConversationSnapshot conversation = service.createConversation(
                "alice",
                "南京到无锡",
                "Asia/Shanghai",
                "alice-conversation-key");

        assertThatThrownBy(() -> service.submit(
                "bob",
                conversation.conversationId(),
                "查询车辆电量",
                "Asia/Shanghai",
                "bob-task-key",
                "trace-bob"))
                .isInstanceOf(AgentResourceNotFoundException.class);
    }

    @Test
    void inMemoryTaskCannotBeReadByAnotherUser() {
        ConversationSnapshot conversation = service.createConversation(
                "alice",
                "南京到无锡",
                "Asia/Shanghai",
                "alice-conversation-key-2");
        AgentTaskAccepted accepted = service.submit(
                "alice",
                conversation.conversationId(),
                "查询车辆电量",
                "Asia/Shanghai",
                "alice-task-key",
                "trace-alice");

        assertThat(service.getTask(accepted.taskId(), "alice").taskId()).isEqualTo(accepted.taskId());
        assertThatThrownBy(() -> service.getTask(accepted.taskId(), "bob"))
                .isInstanceOf(AgentResourceNotFoundException.class);
    }

    @Test
    void userScopedRedisSnapshotRecoversTaskWhenConfiguredDatabaseIsUnavailable() {
        Instant now = Instant.parse("2026-08-05T00:00:00Z");
        AgentTaskSnapshot cached = new AgentTaskSnapshot(
                "199000000000000111",
                "199000000000000011",
                "恢复车辆查询",
                "SUCCEEDED",
                "RULE_STUB",
                "roadmind-rule-fixture",
                true,
                false,
                "已从缓存恢复",
                List.of(),
                0,
                0,
                now,
                now);
        when(taskPersistence.isAvailable()).thenReturn(true);
        when(taskPersistence.findByIdForUser(cached.taskId(), "alice"))
                .thenReturn(Optional.empty());
        when(taskCache.getSnapshot("alice", cached.taskId()))
                .thenReturn(Optional.of(cached));

        AgentTaskSnapshot recovered = service.getTask(cached.taskId(), "alice");

        assertThat(recovered.taskId()).isEqualTo(cached.taskId());
        assertThat(recovered.status()).isEqualTo("SUCCEEDED");
        assertThat(recovered.response()).isEqualTo("已从缓存恢复");
        assertThatThrownBy(() -> service.getTask(cached.taskId(), "bob"))
                .isInstanceOf(AgentResourceNotFoundException.class);
    }

    @Test
    void runningTaskRecoveredAfterRestartBecomesTerminalAndRebuildsSseReplay() {
        Instant now = Instant.parse("2026-08-05T00:00:00Z");
        AgentTaskSnapshot interrupted = new AgentTaskSnapshot(
                "199000000000000211",
                "199000000000000021",
                "重启前运行中的任务",
                "RUNNING",
                "RULE_STUB",
                "roadmind-rule-fixture",
                true,
                false,
                null,
                List.of(),
                0,
                2,
                now,
                now);
        when(taskPersistence.findByIdForUser(interrupted.taskId(), "alice"))
                .thenReturn(Optional.of(interrupted));
        when(eventHub.hasChannel(interrupted.taskId())).thenReturn(false);
        SseEmitter emitter = mock(SseEmitter.class);
        when(eventHub.subscribe(interrupted.taskId())).thenReturn(emitter);

        AgentTaskSnapshot recovered = service.getTask(interrupted.taskId(), "alice");

        assertThat(recovered.status()).isEqualTo("AGENT_RESTARTED");
        assertThat(recovered.response()).contains("服务重启");
        assertThat(service.events(interrupted.taskId(), "alice")).isSameAs(emitter);
        verify(taskPersistence).save(argThat(snapshot ->
                snapshot.taskId().equals(interrupted.taskId())
                        && snapshot.status().equals("AGENT_RESTARTED")));
        verify(taskCache).putSnapshot(
                org.mockito.ArgumentMatchers.eq("alice"),
                argThat(snapshot -> snapshot.status().equals("AGENT_RESTARTED")));
        verify(eventHub).restoreCompleted(argThat(snapshot ->
                snapshot.taskId().equals(interrupted.taskId())
                        && snapshot.status().equals("AGENT_RESTARTED")));
    }

    @Test
    void rejectedWorkflowExecutionBecomesRecoverableTerminalTask() {
        service.shutdown();
        ExecutorService rejectingExecutor = mock(ExecutorService.class);
        doThrow(new RejectedExecutionException("queue full"))
                .when(rejectingExecutor)
                .execute(any(Runnable.class));
        service = new AgentWorkflowService(
                new InMemoryAgentStore(),
                eventHub,
                planner,
                toolRuntime,
                preferenceProvider,
                contextService,
                taskPersistence,
                taskCache,
                new PromptRiskScanner(),
                audit,
                rejectingExecutor);

        ConversationSnapshot conversation = service.createConversation(
                "alice",
                "队列过载测试",
                "Asia/Shanghai",
                "busy-conversation-key");
        AgentTaskAccepted accepted = service.submit(
                "alice",
                conversation.conversationId(),
                "查询车辆电量",
                "Asia/Shanghai",
                "busy-task-key",
                "trace-busy");

        assertThat(accepted.status()).isEqualTo("AGENT_BUSY");
        AgentTaskSnapshot snapshot = service.getTask(accepted.taskId(), "alice");
        assertThat(snapshot.status()).isEqualTo("AGENT_BUSY");
        assertThat(snapshot.response()).contains("任务过多");
        verify(eventHub).complete(accepted.taskId());
    }
}
