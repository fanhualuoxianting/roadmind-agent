package com.roadmind.server.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roadmind.server.tool.RetryPolicy;
import com.roadmind.server.tool.ToolDescriptor;
import com.roadmind.server.tool.ToolRiskLevel;
import com.roadmind.server.tool.ToolRuntime;
import com.roadmind.server.tool.UnknownToolException;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;

class AgentPlannerRouterTest {

    @Test
    void liveModeUsesSpringAiChatModelAndValidatesRegisteredTool() {
        AgentProperties properties = liveProperties();
        ToolRuntime runtime = mock(ToolRuntime.class);
        ToolDescriptor descriptor = descriptor();
        when(runtime.descriptors()).thenReturn(List.of(descriptor));
        when(runtime.descriptor("vehicle.get_status")).thenReturn(descriptor);
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                {"intentSummary":"查询车辆","assistantMessage":"开始查询",\
                "toolCalls":[{"toolName":"vehicle.get_status","arguments":{"vehicleId":"198000000000000401"}}]}
                """);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(chatModel);
        ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        AgentPlannerRouter router = new AgentPlannerRouter(
                properties,
                new RuleStubPlanner(mapper),
                new StructuredPlanParser(mapper),
                runtime,
                provider);

        PlannerDecision decision = router.plan(new PlanningRequest(
                "查询车辆状态",
                ZoneId.of("Asia/Shanghai"),
                "198000000000000401"));

        assertThat(decision.mode()).isEqualTo(AgentMode.LIVE_MODEL);
        assertThat(decision.degraded()).isFalse();
        assertThat(decision.plan().toolCalls()).singleElement()
                .extracting(ModelToolCall::toolName)
                .isEqualTo("vehicle.get_status");
    }

    @Test
    void liveModeWithoutChatModelFailsExplicitlyInsteadOfRunningStub() {
        AgentProperties properties = liveProperties();
        ToolRuntime runtime = mock(ToolRuntime.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        ObjectMapper mapper = new ObjectMapper();
        AgentPlannerRouter router = new AgentPlannerRouter(
                properties,
                new RuleStubPlanner(mapper),
                new StructuredPlanParser(mapper),
                runtime,
                provider);

        assertThatThrownBy(() -> router.plan(new PlanningRequest(
                "查询车辆状态",
                ZoneId.of("Asia/Shanghai"),
                "198000000000000401")))
                .isInstanceOf(AgentModelUnavailableException.class)
                .hasMessageContaining("ChatModel 未配置");
    }

    @Test
    void liveModeRejectsUnknownToolAsInvalidModelOutput() {
        AgentProperties properties = liveProperties();
        ToolRuntime runtime = mock(ToolRuntime.class);
        when(runtime.descriptors()).thenReturn(List.of(descriptor()));
        when(runtime.descriptor("vehicle.delete_everything"))
                .thenThrow(new UnknownToolException("vehicle.delete_everything"));
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                {"intentSummary":"危险操作","assistantMessage":"执行中",\
                "toolCalls":[{"toolName":"vehicle.delete_everything","arguments":{}}]}
                """);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(chatModel);
        ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        AgentPlannerRouter router = new AgentPlannerRouter(
                properties,
                new RuleStubPlanner(mapper),
                new StructuredPlanParser(mapper),
                runtime,
                provider);

        assertThatThrownBy(() -> router.plan(new PlanningRequest(
                "删除所有内容",
                ZoneId.of("Asia/Shanghai"),
                "198000000000000401")))
                .isInstanceOf(InvalidModelOutputException.class)
                .hasMessageContaining("未注册工具");
    }

    @Test
    void liveModeRejectsMalformedModelJsonWithoutFallingBackToRuleStub() {
        AgentProperties properties = liveProperties();
        ToolRuntime runtime = mock(ToolRuntime.class);
        when(runtime.descriptors()).thenReturn(List.of(descriptor()));
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("{not-json");
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(chatModel);
        ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        AgentPlannerRouter router = new AgentPlannerRouter(
                properties,
                new RuleStubPlanner(mapper),
                new StructuredPlanParser(mapper),
                runtime,
                provider);

        assertThatThrownBy(() -> router.plan(request("查询车辆状态")))
                .isInstanceOf(InvalidModelOutputException.class)
                .hasMessageContaining("合法的结构化工具计划");
    }

    @Test
    void liveModeRejectsIncompleteModelToolArgumentsBeforeExecution() {
        AgentProperties properties = liveProperties();
        ToolRuntime runtime = mock(ToolRuntime.class);
        when(runtime.descriptors()).thenReturn(List.of(descriptor()));
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                {"intentSummary":"查询车辆","assistantMessage":"开始查询",
                "toolCalls":[{"toolName":"vehicle.get_status"}]}
                """);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(chatModel);
        ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        AgentPlannerRouter router = new AgentPlannerRouter(
                properties,
                new RuleStubPlanner(mapper),
                new StructuredPlanParser(mapper),
                runtime,
                provider);

        assertThatThrownBy(() -> router.plan(request("查询车辆状态")))
                .isInstanceOf(InvalidModelOutputException.class)
                .hasMessageContaining("合法的结构化工具计划");
    }

    @Test
    void liveModeRejectsHighRiskModelToolBeforeItCanBypassPolicyGate() {
        AgentProperties properties = liveProperties();
        ToolRuntime runtime = mock(ToolRuntime.class);
        ToolDescriptor highRisk = descriptor("home.set_light", ToolRiskLevel.HIGH_RISK_WRITE);
        when(runtime.descriptors()).thenReturn(List.of(highRisk));
        when(runtime.descriptor("home.set_light")).thenReturn(highRisk);
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                {"intentSummary":"关闭灯光","assistantMessage":"执行中",
                "toolCalls":[{"toolName":"home.set_light","arguments":{"deviceId":"demo-light","on":false}}]}
                """);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(chatModel);
        ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        AgentPlannerRouter router = new AgentPlannerRouter(
                properties,
                new RuleStubPlanner(mapper),
                new StructuredPlanParser(mapper),
                runtime,
                provider);

        assertThatThrownBy(() -> router.plan(request("关闭灯光")))
                .isInstanceOf(InvalidModelOutputException.class)
                .hasMessageContaining("Policy Gate");
    }

    @Test
    void liveModeMapsProviderTimeoutToExplicitModelUnavailable() {
        AgentProperties properties = liveProperties();
        ToolRuntime runtime = mock(ToolRuntime.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenThrow(new IllegalStateException("provider timeout"));
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(chatModel);
        ObjectMapper mapper = new ObjectMapper();
        AgentPlannerRouter router = new AgentPlannerRouter(
                properties,
                new RuleStubPlanner(mapper),
                new StructuredPlanParser(mapper),
                runtime,
                provider);

        assertThatThrownBy(() -> router.plan(request("查询车辆状态")))
                .isInstanceOf(AgentModelUnavailableException.class)
                .hasMessageContaining("模型调用失败");
    }

    @Test
    void liveModeRejectsEmptyModelResponseAsInvalidOutput() {
        AgentProperties properties = liveProperties();
        ToolRuntime runtime = mock(ToolRuntime.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn(" ");
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(chatModel);
        ObjectMapper mapper = new ObjectMapper();
        AgentPlannerRouter router = new AgentPlannerRouter(
                properties,
                new RuleStubPlanner(mapper),
                new StructuredPlanParser(mapper),
                runtime,
                provider);

        assertThatThrownBy(() -> router.plan(request("查询车辆状态")))
                .isInstanceOf(InvalidModelOutputException.class)
                .hasMessageContaining("输出为空");
    }

    @Test
    void ruleStubModeHasAnExplicitBoundaryAndDoesNotCallChatModel() {
        AgentProperties properties = new AgentProperties();
        ToolRuntime runtime = mock(ToolRuntime.class);
        ToolDescriptor readOnly = descriptor();
        when(runtime.descriptor("vehicle.get_status")).thenReturn(readOnly);
        ChatModel chatModel = mock(ChatModel.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(chatModel);
        ObjectMapper mapper = new ObjectMapper();
        AgentPlannerRouter router = new AgentPlannerRouter(
                properties,
                new RuleStubPlanner(mapper),
                new StructuredPlanParser(mapper),
                runtime,
                provider);

        PlannerDecision decision = router.plan(request("查询车辆状态"));

        assertThat(decision.mode()).isEqualTo(AgentMode.RULE_STUB);
        org.mockito.Mockito.verifyNoInteractions(chatModel);
    }

    private AgentProperties liveProperties() {
        AgentProperties properties = new AgentProperties();
        properties.setMode(AgentMode.LIVE_MODEL);
        properties.setModelName("compatible-model");
        return properties;
    }

    private ToolDescriptor descriptor() {
        return descriptor("vehicle.get_status", ToolRiskLevel.READ_ONLY);
    }

    private ToolDescriptor descriptor(String name, ToolRiskLevel riskLevel) {
        return new ToolDescriptor(
                name,
                "1.0.0",
                "query vehicle",
                riskLevel,
                Duration.ofSeconds(2),
                RetryPolicy.none(),
                true,
                "vehicle-schema");
    }

    private PlanningRequest request(String message) {
        return new PlanningRequest(message, ZoneId.of("Asia/Shanghai"), "198000000000000401");
    }
}
