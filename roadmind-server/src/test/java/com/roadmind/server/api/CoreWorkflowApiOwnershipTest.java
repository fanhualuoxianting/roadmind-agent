package com.roadmind.server.api;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roadmind.server.workflow.CoreWorkflowCache;
import com.roadmind.server.workflow.CoreWorkflowPersistence;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.ai.model.chat=none",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class CoreWorkflowApiOwnershipTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CoreWorkflowPersistence persistence;

    @MockitoBean
    private CoreWorkflowCache cache;

    @BeforeEach
    void setUp() {
        when(persistence.findLatestByConversationForUser(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(persistence.findByIdForUser(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(cache.findByConversation(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(cache.get(anyString(), anyString()))
                .thenReturn(Optional.empty());
    }

    @Test
    void workflowIdCannotBeReadOrApprovedByAnotherAuthenticatedUser() throws Exception {
        String conversationId = "shared-conversation-id";
        JsonNode alice = createPlan("alice", conversationId, "明天早上8点从南京软件谷出发去苏州");
        String workflowId = alice.path("workflowId").asText();

        mockMvc.perform(get("/api/v1/workflows/{workflowId}", workflowId)
                        .with(nonLoopback())
                        .with(user("bob")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        String decision = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("decision", "APPROVE")
                .put("planVersion", alice.path("planVersion").asInt())
                .put("payloadHash", alice.path("confirmation").path("payloadHash").asText()));
        mockMvc.perform(post("/api/v1/workflows/{workflowId}/confirmation-decisions", workflowId)
                        .with(nonLoopback())
                        .with(user("bob"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decision))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        JsonNode bob = createPlan("bob", conversationId, "明天早上9点从南京南站出发去无锡");
        org.assertj.core.api.Assertions.assertThat(bob.path("workflowId").asText())
                .isNotEqualTo(workflowId);
    }

    private JsonNode createPlan(String username, String conversationId, String message) throws Exception {
        String body = objectMapper.writeValueAsString(
                objectMapper.createObjectNode().put("message", message));
        MvcResult result = mockMvc.perform(post(
                        "/api/v1/conversations/{conversationId}/workflow-messages",
                        conversationId)
                        .with(nonLoopback())
                        .with(user(username))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WAITING_CONFIRMATION"))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }

    private RequestPostProcessor nonLoopback() {
        return request -> {
            request.setRemoteAddr("203.0.113.10");
            return request;
        };
    }
}
