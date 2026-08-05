package com.roadmind.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class McpToolBridge {

    private static final int MAX_TEXT_ARGUMENT_LENGTH = 128;

    private final RestClient server;
    private final McpBridgeProperties properties;

    public McpToolBridge(RestClient roadMindServerClient, McpBridgeProperties properties) {
        this.server = roadMindServerClient;
        this.properties = properties;
    }

    @McpTool(name = "vehicle.get_status", description = "读取 RoadMind 数字孪生车辆状态", generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public McpToolResult vehicleGetStatus(
            @McpToolParam(description = "车辆 ID", required = true) String vehicleId) {
        return invoke("vehicle.get_status", Map.of("vehicleId", requireText("vehicleId", vehicleId)));
    }

    @McpTool(name = "weather.get_forecast", description = "查询带来源标记的天气预报", generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true))
    public McpToolResult weatherGetForecast(
            @McpToolParam(description = "地点", required = true) String location,
            @McpToolParam(description = "日期，ISO-8601", required = true) String date) {
        LocalDate parsed;
        try {
            parsed = LocalDate.parse(requireText("date", date));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("date 必须是 ISO-8601 日期");
        }
        return invoke("weather.get_forecast", Map.of(
                "location", requireText("location", location), "date", parsed));
    }

    @McpTool(name = "route.plan", description = "规划带来源标记的驾车路线摘要", generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true))
    public McpToolResult routePlan(
            @McpToolParam(description = "起点", required = true) String origin,
            @McpToolParam(description = "终点", required = true) String destination,
            @McpToolParam(description = "是否避开拥堵", required = true) boolean avoidTraffic) {
        return invoke("route.plan", Map.of(
                "origin", requireText("origin", origin),
                "destination", requireText("destination", destination),
                "avoidTraffic", avoidTraffic));
    }

    private String requireText(String name, String value) {
        if (value == null || value.isBlank() || value.length() > MAX_TEXT_ARGUMENT_LENGTH) {
            throw new IllegalArgumentException(name + " 不能为空且长度不能超过 " + MAX_TEXT_ARGUMENT_LENGTH);
        }
        return value.trim();
    }

    private McpToolResult invoke(String toolName, Object arguments) {
        String requestId = UUID.randomUUID().toString();
        try {
            McpToolResult result = server.post()
                    .uri("/internal/v1/tools/{toolName}", toolName)
                    .header("X-RoadMind-Internal-Token", properties.internalToken())
                    .header("X-MCP-Request-ID", requestId)
                    .body(arguments)
                    .retrieve()
                    .body(McpToolResult.class);
            if (result == null) throw new IllegalStateException("主 Server 未返回 MCP 工具结果");
            return result;
        } catch (RestClientResponseException exception) {
            throw new IllegalStateException("RoadMind 主 Server 拒绝 MCP 工具调用: " + exception.getStatusCode());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record McpToolResult(
            boolean success,
            String toolName,
            String toolVersion,
            String executionId,
            Object result,
            String errorCode,
            String errorMessage,
            boolean retryable,
            int attempts,
            String traceId) {

        public McpToolResult {
            // The main Server omits nullable error fields on successful calls. MCP's generated
            // output schema marks record components as required, so normalize the wire shape at
            // this boundary instead of weakening the protocol contract.
            toolName = toolName == null ? "" : toolName;
            toolVersion = toolVersion == null ? "" : toolVersion;
            executionId = executionId == null ? "" : executionId;
            result = result == null ? Map.of() : result;
            errorCode = errorCode == null ? "" : errorCode;
            errorMessage = errorMessage == null ? "" : errorMessage;
            traceId = traceId == null ? "" : traceId;
        }
    }
}
