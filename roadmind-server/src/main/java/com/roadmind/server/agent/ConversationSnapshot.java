package com.roadmind.server.agent;

import java.time.Instant;

public record ConversationSnapshot(
        String conversationId,
        String title,
        String status,
        String timezone,
        Instant createdAt) {
}
