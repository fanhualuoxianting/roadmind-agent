package com.roadmind.server.conversation;

import com.roadmind.server.agent.ConversationSnapshot;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Coordinates durable conversation state, Redis caching, and restart recovery.
 */
@Service
public class ConversationContextService {

    private final ConversationPersistence persistence;
    private final ConversationContextCache cache;

    public ConversationContextService(
            ConversationPersistence persistence,
            ConversationContextCache cache) {
        this.persistence = persistence;
        this.cache = cache;
    }

    public void created(String username, ConversationSnapshot conversation) {
        if (!persistence.isAvailable()) {
            return;
        }
        persistence.create(conversation, username).ifPresent(cache::put);
    }

    public Optional<ConversationContextSnapshot> appendUserMessage(
            String username,
            String conversationId,
            String content) {
        if (!persistence.isAvailable()) {
            return Optional.empty();
        }
        long userId = persistence.requireUserId(username);
        Optional<ConversationContextSnapshot> snapshot = persistence.appendUserMessage(
                userId,
                conversationId,
                content,
                Instant.now());
        snapshot.ifPresent(cache::put);
        return snapshot;
    }

    public Optional<ConversationContextSnapshot> recover(
            String username,
            String conversationId) {
        if (!persistence.isAvailable()) {
            return Optional.empty();
        }
        long userId = persistence.requireUserId(username);
        Optional<ConversationContextSnapshot> cached = cache.get(conversationId)
                .filter(snapshot -> snapshot.userId() == userId);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<ConversationContextSnapshot> persistent = persistence.findActive(
                userId,
                conversationId,
                Instant.now());
        persistent.ifPresent(cache::put);
        return persistent;
    }
}
