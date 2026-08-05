package com.roadmind.mcp;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "roadmind.bridge.internal-token=test-mcp-token",
        "spring.ai.mcp.server.protocol=STATELESS"
})
@AutoConfigureMockMvc
class McpProtocolTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void missingTokenIsRejectedBeforeMcpProtocolHandling() throws Exception {
        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(initializeRequest()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void initializeAndToolsListExposeTheControlledReadOnlyCatalog() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header("X-RoadMind-MCP-Token", "test-mcp-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .content(initializeRequest()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("roadmind-controlled-tools")));

        mockMvc.perform(post("/mcp")
                        .header("X-RoadMind-MCP-Token", "test-mcp-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .content("""
                                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                                """))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("vehicle.get_status")))
                .andExpect(content().string(containsString("weather.get_forecast")))
                .andExpect(content().string(containsString("route.plan")));
    }

    @Test
    void unknownToolReturnsProtocolErrorWithoutCallingTheMainServer() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header("X-RoadMind-MCP-Token", "test-mcp-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .content("""
                                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"admin.delete_everything","arguments":{}}}
                                """))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"error\"")));
    }

    @Test
    void oversizedToolParameterReturnsProtocolError() throws Exception {
        String oversized = "x".repeat(129);
        mockMvc.perform(post("/mcp")
                        .header("X-RoadMind-MCP-Token", "test-mcp-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .content("""
                                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"vehicle.get_status","arguments":{"vehicleId":"%s"}}}
                """.formatted(oversized)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"isError\":true")));
    }

    private String initializeRequest() {
        return """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"roadmind-test","version":"1.0"}}}
                """;
    }
}
