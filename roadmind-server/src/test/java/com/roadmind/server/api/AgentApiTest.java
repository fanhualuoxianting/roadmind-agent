package com.roadmind.server.api;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roadmind.server.vehicle.domain.GeoLocation;
import com.roadmind.server.vehicle.domain.TirePressure;
import com.roadmind.server.vehicle.domain.VehicleGateway;
import com.roadmind.server.vehicle.domain.VehicleStatus;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.ai.model.chat=none",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class AgentApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private VehicleGateway gateway;

    @BeforeEach
    void setUp() {
        when(gateway.getStatus(anyString())).thenReturn(vehicle());
    }

    @Test
    void streamsOrderedRuleStubToolLifecycleAndFinalResponse() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String conversationId = createConversation(session, "conversation-key-1");
        String taskId = submit(session, conversationId, "task-key-1", scenario());
        JsonNode task = waitForTerminalTask(session, taskId);

        org.assertj.core.api.Assertions.assertThat(task.path("data").path("status").asText())
                .isEqualTo("SUCCEEDED");
        org.assertj.core.api.Assertions.assertThat(task.path("data").path("plannerMode").asText())
                .isEqualTo("RULE_STUB");
        org.assertj.core.api.Assertions.assertThat(task.path("data").path("degraded").asBoolean())
                .isTrue();
        org.assertj.core.api.Assertions.assertThat(task.path("data").path("toolCalls").size())
                .isEqualTo(3);
        org.assertj.core.api.Assertions.assertThat(task.toString())
                .contains("DIGITAL_TWIN", "\"sourceMode\":\"STUB\"");

        MvcResult stream = mockMvc.perform(get("/api/v1/agent-tasks/{taskId}/events", taskId)
                        .session(session)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult completed = mockMvc.perform(asyncDispatch(stream))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event:agent.plan.created")))
                .andExpect(content().string(containsString("event:tool.call.started")))
                .andExpect(content().string(containsString("event:tool.call.completed")))
                .andExpect(content().string(containsString("event:agent.response.ready")))
                .andExpect(content().string(containsString("event:stream.complete")))
                .andReturn();

        String body = completed.getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body.indexOf("event:agent.plan.created"))
                .isLessThan(body.indexOf("event:tool.call.started"));
        org.assertj.core.api.Assertions.assertThat(body.indexOf("event:tool.call.started"))
                .isLessThan(body.indexOf("event:tool.call.completed"));
        org.assertj.core.api.Assertions.assertThat(body.indexOf("event:agent.response.ready"))
                .isLessThan(body.indexOf("event:stream.complete"));
    }

    @Test
    void sameIdempotencyKeyWithDifferentMessageReturns409() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String conversationId = createConversation(session, "conversation-key-2");
        submit(session, conversationId, "shared-task-key", "查询车辆电量");

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/agent-requests", conversationId)
                        .session(session)
                        .with(csrf())
                        .header("Idempotency-Key", "shared-task-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"查询一条不同路线\",\"clientContext\":{\"timezone\":\"Asia/Shanghai\"}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void invalidConversationTimezoneReturnsValidationError() throws Exception {
        mockMvc.perform(post("/api/v1/conversations")
                        .session(new MockHttpSession())
                        .with(csrf())
                        .header("Idempotency-Key", "invalid-timezone-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"无效时区\",\"timezone\":\"Mars/Olympus\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private String createConversation(MockHttpSession session, String key) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/conversations")
                        .session(session)
                        .with(csrf())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"南京到无锡\",\"timezone\":\"Asia/Shanghai\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("data").path("conversationId").asText();
    }

    private String submit(MockHttpSession session, String conversationId, String key, String message)
            throws Exception {
        String body = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("message", message)
                .set("clientContext", objectMapper.createObjectNode().put("timezone", "Asia/Shanghai")));
        MvcResult result = mockMvc.perform(post("/api/v1/conversations/{conversationId}/agent-requests", conversationId)
                        .session(session)
                        .with(csrf())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("data").path("taskId").asText();
    }

    private JsonNode waitForTerminalTask(MockHttpSession session, String taskId) throws Exception {
        JsonNode task = null;
        for (int attempt = 0; attempt < 50; attempt++) {
            MvcResult result = mockMvc.perform(get("/api/v1/agent-tasks/{taskId}", taskId).session(session))
                    .andExpect(status().isOk())
                    .andReturn();
            task = objectMapper.readTree(result.getResponse().getContentAsByteArray());
            String status = task.path("data").path("status").asText();
            if (!status.equals("ACCEPTED") && !status.equals("PLANNING") && !status.equals("RUNNING")) {
                return task;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Agent task did not complete: " + task);
    }

    private String scenario() {
        return "明天早上 8 点从南京软件谷出发去无锡学院，避开拥堵，电量不足时安排充电，出发前打开空调，到达后关闭家里的灯。";
    }

    private VehicleStatus vehicle() {
        return new VehicleStatus(
                "demo-vehicle-001",
                "RoadMind Demo Car",
                "DIGITAL_TWIN",
                68.0,
                412.0,
                29.0,
                true,
                false,
                "P",
                new GeoLocation("GCJ-02", 118.7969, 32.0603, "南京"),
                new TirePressure(2.4, 2.4, 2.3, 2.3),
                12,
                Instant.parse("2026-08-04T00:00:00Z"));
    }
}
