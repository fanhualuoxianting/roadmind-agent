package com.roadmind.server.conversation;

import java.time.Instant;
import java.util.List;

/**
 * Durable conversation context used to rebuild the in-memory workflow after a restart.
 */
public record ConversationContextSnapshot(
        long userId,
        String conversationId,
        String title,
        String status,
        String timezone,
        Instant createdAt,
        int contextVersion,
        List<String> recentMessages,
        Instant expiresAt) {

    public ConversationContextSnapshot {
        recentMessages = recentMessages == null ? List.of() : List.copyOf(recentMessages);
    }
}
