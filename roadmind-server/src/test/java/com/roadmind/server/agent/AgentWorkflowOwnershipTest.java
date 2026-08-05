package com.roadmind.server.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.roadmind.server.audit.AuditService;
import com.roadmind.server.conversation.ConversationContextService;
import com.roadmind.server.preference.PreferenceService;
import com.roadmind.server.tool.ToolRuntime;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class AgentWorkflowOwnershipTest {

    private AgentWorkflowService service;
    private AgentTaskPersistence taskPersistence;
    private AgentTaskCache taskCache;

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<PreferenceService> preferenceProvider = mock(ObjectProvider.class);
        taskPersistence = mock(AgentTaskPersistence.class);
        taskCache = mock(AgentTaskCache.class);
        when(taskPersistence.findByIdempotencyKey(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(taskPersistence.isAvailable()).thenReturn(false);
        when(taskCache.findIdempotency(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        service = new AgentWorkflowService(
                new InMemoryAgentStore(),
                mock(AgentEventHub.class),
                mock(AgentPlannerRouter.class),
                mock(ToolRuntime.class),
                preferenceProvider,
                mock(ConversationContextService.class),
                taskPersistence,
                taskCache,
                new PromptRiskScanner(),
                mock(AuditService.class));
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
}
