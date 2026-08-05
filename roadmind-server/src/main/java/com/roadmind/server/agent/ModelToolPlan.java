package com.roadmind.server.agent;

import java.util.List;

public record ModelToolPlan(
        String intentSummary,
        String assistantMessage,
        List<ModelToolCall> toolCalls) {

    public ModelToolPlan {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }
}
