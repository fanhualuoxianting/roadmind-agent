package com.roadmind.server.api;

import com.roadmind.server.agent.AgentPlannerRouter;
import com.roadmind.server.agent.AgentProperties;
import com.roadmind.server.config.RoadMindExternalProperties;
import com.roadmind.server.tool.ToolRuntime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/settings")
public class CapabilitiesController {

    private final AgentProperties agentProperties;
    private final RoadMindExternalProperties externalProperties;
    private final AgentPlannerRouter planner;
    private final ToolRuntime tools;

    public CapabilitiesController(
            AgentProperties agentProperties,
            RoadMindExternalProperties externalProperties,
            AgentPlannerRouter planner,
            ToolRuntime tools) {
        this.agentProperties = agentProperties;
        this.externalProperties = externalProperties;
        this.planner = planner;
        this.tools = tools;
    }

    @GetMapping("/capabilities")
    ApiResponse<Map<String, Object>> capabilities() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agentMode", agentProperties.getMode().name());
        data.put("liveModelAvailable", planner.liveModelAvailable());
        data.put("modelName", agentProperties.getModelName());
        data.put("weatherSourceMode", externalProperties.getWeatherMode());
        data.put("routeSourceMode", externalProperties.getRouteMode());
        data.put("toolCount", tools.descriptors().size());
        data.put("tools", tools.descriptors());
        data.put("writesEnabled", false);
        data.put("vehicleMode", "DIGITAL_TWIN");
        return ApiResponse.ok(data);
    }

    @GetMapping("/model-status")
    ApiResponse<Map<String, Object>> modelStatus() {
        return ApiResponse.ok(Map.of(
                "configuredMode", agentProperties.getMode().name(),
                "liveModelAvailable", planner.liveModelAvailable(),
                "modelName", agentProperties.getModelName(),
                "safeDefaultMode", "RULE_STUB",
                "automaticFallbackEnabled", false));
    }
}
