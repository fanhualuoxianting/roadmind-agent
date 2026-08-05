package com.roadmind.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roadmind.server.adapter.simulator.ScheduledClimateCommand;
import com.roadmind.server.vehicle.domain.GeoLocation;
import com.roadmind.server.vehicle.domain.TirePressure;
import com.roadmind.server.vehicle.domain.VehicleGateway;
import com.roadmind.server.vehicle.domain.VehicleNativeScheduler;
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

@SpringBootTest(properties = {"spring.flyway.enabled=false", "spring.ai.model.chat=none", "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class CoreWorkflowApiTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean VehicleGateway gateway;
    @MockitoBean VehicleNativeScheduler vehicleScheduler;

    @BeforeEach void setUp() {
        when(gateway.getStatus(anyString())).thenReturn(new VehicleStatus("demo", "Demo", "DIGITAL_TWIN", 68, 412, 29, true, false, "P", new GeoLocation("GCJ-02", 118.7, 32.0, "南京"), new TirePressure(2.4,2.4,2.4,2.4), 1, Instant.now()));
        when(vehicleScheduler.scheduleClimate(anyString(), any(Instant.class), anyDouble(), anyString()))
                .thenReturn(new ScheduledClimateCommand("scheduled-demo", "demo-vehicle-001", Instant.now().plusSeconds(3600), 24, "PENDING", "idempotency", Instant.now(), null, null));
    }

    @Test void clarificationPlanAndBoundConfirmationFormHttpLoop() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String conversationId = conversation(session);
        mockMvc.perform(post("/api/v1/conversations/{id}/workflow-messages", conversationId).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"明天去苏州\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("WAITING_INPUT"));
        MvcResult planned = mockMvc.perform(post("/api/v1/conversations/{id}/workflow-messages", conversationId).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"明天早上8点从南京软件谷出发去苏州\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("WAITING_CONFIRMATION")).andExpect(jsonPath("$.data.steps.length()").value(5)).andReturn();
        JsonNode data = mapper.readTree(planned.getResponse().getContentAsByteArray()).path("data");
        String body = mapper.writeValueAsString(mapper.createObjectNode().put("decision", "APPROVE").put("planVersion", data.path("planVersion").asInt()).put("payloadHash", data.path("confirmation").path("payloadHash").asText()));
        mockMvc.perform(post("/api/v1/workflows/{id}/confirmation-decisions", data.path("workflowId").asText()).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
    }

    private String conversation(MockHttpSession session) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/conversations").session(session).with(csrf()).header("Idempotency-Key", "phase3-conversation").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"阶段3\",\"timezone\":\"Asia/Shanghai\"}"))
                .andExpect(status().isCreated()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsByteArray()).path("data").path("conversationId").asText();
    }
}
