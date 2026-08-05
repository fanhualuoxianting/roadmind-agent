package com.roadmind.server.agent;

public record PlannerDecision(
        ModelToolPlan plan,
        AgentMode mode,
        String modelName,
        boolean degraded,
        boolean jsonRepaired,
        Integer inputTokens,
        Integer outputTokens,
        boolean tokenUsageEstimated) {
}
