package com.roadmind.server.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class RuleStubPlannerTest {

    private final RuleStubPlanner planner = new RuleStubPlanner(new ObjectMapper());

    @Test
    void selectsThreeReadOnlyToolsForThePortfolioScenario() {
        ModelToolPlan plan = planner.plan(new PlanningRequest(
                "明天早上 8 点从南京软件谷出发去无锡学院，避开拥堵，电量不足时安排充电，出发前打开空调，到达后关闭家里的灯。",
                ZoneId.of("Asia/Shanghai"),
                "198000000000000401"));

        assertThat(plan.toolCalls())
                .extracting(ModelToolCall::toolName)
                .containsExactly("vehicle.get_status", "weather.get_forecast", "route.plan");
        assertThat(plan.toolCalls().get(2).arguments().path("origin").asText()).isEqualTo("南京软件谷");
        assertThat(plan.toolCalls().get(2).arguments().path("destination").asText()).isEqualTo("无锡学院");
        assertThat(plan.assistantMessage()).contains("只读查询");
    }
}
