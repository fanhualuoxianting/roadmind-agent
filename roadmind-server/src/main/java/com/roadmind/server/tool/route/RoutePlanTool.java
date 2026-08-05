package com.roadmind.server.tool.route;

import com.roadmind.server.route.RouteGateway;
import com.roadmind.server.route.RouteSummary;
import com.roadmind.server.tool.RetryPolicy;
import com.roadmind.server.tool.RoadMindTool;
import com.roadmind.server.tool.ToolDescriptor;
import com.roadmind.server.tool.ToolExecutionContext;
import com.roadmind.server.tool.ToolRiskLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class RoutePlanTool implements RoadMindTool<RoutePlanTool.Input, RouteSummary> {

    private static final ToolDescriptor DESCRIPTOR = new ToolDescriptor(
            "route.plan",
            "1.0.0",
            "规划驾车路线摘要，并保留 LIVE/STUB 来源",
            ToolRiskLevel.READ_ONLY,
            Duration.ofSeconds(5),
            RetryPolicy.retryOnce(Duration.ofMillis(100)),
            true,
            "classpath:/tool-schemas/route.plan.json");

    private final RouteGateway gateway;

    public RoutePlanTool(RouteGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public ToolDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    @Override
    public RouteSummary execute(Input input, ToolExecutionContext context) {
        return gateway.plan(input.origin(), input.destination(), input.avoidTraffic());
    }

    public record Input(
            @NotBlank @Size(max = 120) String origin,
            @NotBlank @Size(max = 120) String destination,
            boolean avoidTraffic) {
    }
}
