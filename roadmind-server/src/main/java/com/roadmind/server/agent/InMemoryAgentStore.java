package com.roadmind.server.agent;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class InMemoryAgentStore {

    /*
     * IDs must not restart at the same value after a JVM restart: conversations are now
     * persisted in MySQL, so reusing the old in-memory counter could overwrite a durable row.
     */
    private final AtomicLong conversationSequence = new AtomicLong(nextSequence());
    private final AtomicLong taskSequence = new AtomicLong(nextSequence() + 1000);
    private final Map<String, ConversationSnapshot> conversations = new ConcurrentHashMap<>();
    private final Map<String, MutableTask> tasks = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryAgentStore() {
        this(Clock.systemUTC());
    }

    InMemoryAgentStore(Clock clock) {
        this.clock = clock;
    }

    private static long nextSequence() {
        return System.currentTimeMillis() * 100_000L
                + ThreadLocalRandom.current().nextLong(100_000L);
    }

    public ConversationSnapshot createConversation(String title, String timezone) {
        String id = Long.toString(conversationSequence.getAndIncrement());
        ConversationSnapshot conversation = new ConversationSnapshot(
                id,
                title,
                "ACTIVE",
                timezone,
                clock.instant());
        conversations.put(id, conversation);
        return conversation;
    }

    public ConversationSnapshot requireConversation(String id) {
        ConversationSnapshot conversation = conversations.get(id);
        if (conversation == null) {
            throw new AgentResourceNotFoundException("会话", id);
        }
        return conversation;
    }

    public void restoreConversation(ConversationSnapshot conversation) {
        conversations.putIfAbsent(conversation.conversationId(), conversation);
    }

    public AgentTaskSnapshot createTask(String conversationId, String goal) {
        requireConversation(conversationId);
        String id = Long.toString(taskSequence.getAndIncrement());
        Instant now = clock.instant();
        MutableTask task = new MutableTask(id, conversationId, goal, now);
        tasks.put(id, task);
        return task.snapshot();
    }

    public AgentTaskSnapshot requireTask(String taskId) {
        MutableTask task = tasks.get(taskId);
        if (task == null) {
            throw new AgentResourceNotFoundException("Agent 任务", taskId);
        }
        return task.snapshot();
    }

    public void restoreTask(AgentTaskSnapshot snapshot) {
        tasks.putIfAbsent(snapshot.taskId(), MutableTask.from(snapshot));
    }

    public void planning(String taskId) {
        task(taskId).updateStatus("PLANNING", clock.instant());
    }

    public void planned(String taskId, PlannerDecision decision) {
        task(taskId).planned(decision, clock.instant());
    }

    public void running(String taskId) {
        task(taskId).updateStatus("RUNNING", clock.instant());
    }

    public void addToolCall(String taskId, AgentToolCallSnapshot call) {
        task(taskId).addToolCall(call, clock.instant());
    }

    public void complete(String taskId, String status, String response) {
        task(taskId).complete(status, response, clock.instant());
    }

    private MutableTask task(String taskId) {
        MutableTask task = tasks.get(taskId);
        if (task == null) {
            throw new AgentResourceNotFoundException("Agent 任务", taskId);
        }
        return task;
    }

    private static final class MutableTask {
        private final String id;
        private final String conversationId;
        private final String goal;
        private final Instant createdAt;
        private final List<AgentToolCallSnapshot> toolCalls = new ArrayList<>();
        private String status = "ACCEPTED";
        private String plannerMode;
        private String modelName;
        private boolean degraded;
        private boolean jsonRepaired;
        private String response;
        private int totalToolCalls;
        private Instant updatedAt;

        private MutableTask(String id, String conversationId, String goal, Instant now) {
            this.id = id;
            this.conversationId = conversationId;
            this.goal = goal;
            this.createdAt = now;
            this.updatedAt = now;
        }

        private static MutableTask from(AgentTaskSnapshot snapshot) {
            MutableTask task = new MutableTask(
                    snapshot.taskId(),
                    snapshot.conversationId(),
                    snapshot.goal(),
                    snapshot.createdAt());
            task.status = snapshot.status();
            task.plannerMode = snapshot.plannerMode();
            task.modelName = snapshot.modelName();
            task.degraded = snapshot.degraded();
            task.jsonRepaired = snapshot.jsonRepaired();
            task.response = snapshot.response();
            task.totalToolCalls = snapshot.totalToolCalls();
            task.updatedAt = snapshot.updatedAt();
            if (snapshot.toolCalls() != null) {
                task.toolCalls.addAll(snapshot.toolCalls());
            }
            return task;
        }

        synchronized void updateStatus(String status, Instant now) {
            this.status = status;
            this.updatedAt = now;
        }

        synchronized void planned(PlannerDecision decision, Instant now) {
            this.plannerMode = decision.mode().name();
            this.modelName = decision.modelName();
            this.degraded = decision.degraded();
            this.jsonRepaired = decision.jsonRepaired();
            this.totalToolCalls = decision.plan().toolCalls().size();
            this.updatedAt = now;
        }

        synchronized void addToolCall(AgentToolCallSnapshot call, Instant now) {
            toolCalls.add(call);
            updatedAt = now;
        }

        synchronized void complete(String status, String response, Instant now) {
            this.status = status;
            this.response = response;
            this.updatedAt = now;
        }

        synchronized AgentTaskSnapshot snapshot() {
            return new AgentTaskSnapshot(
                    id,
                    conversationId,
                    goal,
                    status,
                    plannerMode,
                    modelName,
                    degraded,
                    jsonRepaired,
                    response,
                    List.copyOf(toolCalls),
                    toolCalls.size(),
                    totalToolCalls,
                    createdAt,
                    updatedAt);
        }
    }
}
