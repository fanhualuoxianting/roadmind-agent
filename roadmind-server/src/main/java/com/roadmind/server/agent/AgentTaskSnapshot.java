package com.roadmind.server.agent;

import java.time.Instant;
import java.util.List;

public record AgentTaskSnapshot(
        String taskId,
        String conversationId,
        String goal,
        String status,
        String plannerMode,
        String modelName,
        boolean degraded,
        boolean jsonRepaired,
        String response,
        List<AgentToolCallSnapshot> toolCalls,
        int completedToolCalls,
        int totalToolCalls,
        Instant createdAt,
        Instant updatedAt) {
}
