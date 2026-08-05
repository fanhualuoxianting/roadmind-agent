package com.roadmind.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.roadmind.server.shared.RequestTrace;
import com.roadmind.server.tool.ToolExecutionContext;
import com.roadmind.server.tool.ToolExecutionResult;
import com.roadmind.server.tool.ToolRiskLevel;
import com.roadmind.server.tool.ToolRuntime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Narrow, token-protected bridge used by the separate MCP process. */
@RestController
@RequestMapping("/internal/v1/tools")
public class InternalToolController {

    private final ToolRuntime tools;
    private final String internalToken;

    public InternalToolController(
            ToolRuntime tools,
            @Value("${roadmind.internal.token:disabled}") String internalToken) {
        this.tools = tools;
        this.internalToken = internalToken;
    }

    @PostMapping("/{toolName}")
    ToolExecutionResult execute(
            @PathVariable String toolName,
            @RequestHeader("X-RoadMind-Internal-Token") String token,
            @RequestHeader(value = "X-MCP-Request-ID", required = false) String requestId,
            @RequestBody JsonNode arguments) {
        if (!validInternalToken(token)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "internal token 无效");
        }
        if (tools.descriptor(toolName).riskLevel() != ToolRiskLevel.READ_ONLY) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "MCP 不能绕过 Policy Gate 执行高风险工具");
        }
        return tools.execute(
                toolName,
                arguments,
                new ToolExecutionContext("mcp", requestId == null ? UUID.randomUUID().toString() : requestId,
                        RequestTrace.current()));
    }

    boolean validInternalToken(String candidate) {
        if (internalToken == null
                || internalToken.isBlank()
                || "disabled".equals(internalToken)
                || candidate == null) {
            return false;
        }
        return MessageDigest.isEqual(
                internalToken.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
    }
}
