package com.roadmind.server.conversation;

import com.roadmind.server.agent.ConversationSnapshot;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Service;

/**
 * Coordinates durable conversation state, user-scoped Redis caching, and restart recovery.
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
        try {
            persistence.create(conversation, username)
                    .ifPresent(snapshot -> cache.put(username, snapshot));
        } catch (DataAccessResourceFailureException ignored) {
            // The in-memory conversation remains usable while MySQL is unavailable.
        }
    }

    public Optional<ConversationContextSnapshot> appendUserMessage(
            String username,
            String conversationId,
            String content) {
        if (!persistence.isAvailable()) {
            return Optional.empty();
        }
        try {
            long userId = persistence.requireUserId(username);
            Optional<ConversationContextSnapshot> snapshot = persistence.appendUserMessage(
                    userId,
                    conversationId,
                    content,
                    Instant.now());
            snapshot.ifPresent(value -> cache.put(username, value));
            return snapshot;
        } catch (DataAccessResourceFailureException ignored) {
            return Optional.empty();
        }
    }

    public Optional<ConversationContextSnapshot> recover(
            String username,
            String conversationId) {
        Optional<ConversationContextSnapshot> cached = cache.get(username, conversationId);
        if (cached.isPresent()) {
            return cached;
        }
        if (!persistence.isAvailable()) {
            return Optional.empty();
        }
        try {
            long userId = persistence.requireUserId(username);
            Optional<ConversationContextSnapshot> persistent = persistence.findActive(
                    userId,
                    conversationId,
                    Instant.now());
            persistent.ifPresent(value -> cache.put(username, value));
            return persistent;
        } catch (DataAccessResourceFailureException ignored) {
            return Optional.empty();
        }
    }
}
