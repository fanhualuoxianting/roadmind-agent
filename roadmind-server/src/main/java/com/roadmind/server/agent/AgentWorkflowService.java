package com.roadmind.server.agent;

import com.roadmind.server.audit.AuditService;
import com.roadmind.server.route.RouteSummary;
import com.roadmind.server.tool.ToolExecutionContext;
import com.roadmind.server.tool.ToolExecutionResult;
import com.roadmind.server.tool.ToolRuntime;
import com.roadmind.server.vehicle.application.VehicleApplicationService;
import com.roadmind.server.vehicle.domain.VehicleStatus;
import com.roadmind.server.weather.WeatherForecast;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.roadmind.server.preference.PreferenceService;
import com.roadmind.server.conversation.ConversationContextService;
import com.roadmind.server.conversation.ConversationContextSnapshot;

@Service
public class AgentWorkflowService {

    private final InMemoryAgentStore store;
    private final AgentEventHub eventHub;
    private final AgentPlannerRouter planner;
    private final ToolRuntime toolRuntime;
    private final ObjectProvider<PreferenceService> preferenceService;
    private final ConversationContextService contextService;
    private final AgentTaskPersistence taskPersistence;
    private final AgentTaskCache taskCache;
    private final PromptRiskScanner promptRiskScanner;
    private final AuditService audit;
    private final ExecutorService executor;
    private final Map<String, IdempotencyEntry> conversationRequests = new ConcurrentHashMap<>();
    private final Map<String, IdempotencyEntry> taskRequests = new ConcurrentHashMap<>();
    private final Map<String, String> taskOwners = new ConcurrentHashMap<>();

    public AgentWorkflowService(
            InMemoryAgentStore store,
            AgentEventHub eventHub,
            AgentPlannerRouter planner,
            ToolRuntime toolRuntime,
            ObjectProvider<PreferenceService> preferenceService,
            ConversationContextService contextService,
            AgentTaskPersistence taskPersistence,
            AgentTaskCache taskCache,
            PromptRiskScanner promptRiskScanner,
            AuditService audit) {
        this.store = store;
        this.eventHub = eventHub;
        this.planner = planner;
        this.toolRuntime = toolRuntime;
        this.preferenceService = preferenceService;
        this.contextService = contextService;
        this.taskPersistence = taskPersistence;
        this.taskCache = taskCache;
        this.promptRiskScanner = promptRiskScanner;
        this.audit = audit;
        this.executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "roadmind-agent-workflow");
            thread.setDaemon(true);
            return thread;
        });
    }

    public ConversationSnapshot createConversation(
            String userId,
            String title,
            String timezone,
            String idempotencyKey) {
        parseTimezone(timezone);
        String scopeKey = userId + ":conversation:" + idempotencyKey;
        String fingerprint = sha256(title + "\n" + timezone);
        synchronized (conversationRequests) {
            IdempotencyEntry existing = conversationRequests.get(scopeKey);
            if (existing != null) {
                ensureSame(existing, fingerprint);
                return store.requireConversation(existing.resourceId());
            }
            ConversationSnapshot conversation = store.createConversation(title, timezone);
            contextService.created(userId, conversation);
            conversationRequests.put(
                    scopeKey,
                    new IdempotencyEntry(fingerprint, conversation.conversationId()));
            return conversation;
        }
    }

    public AgentTaskAccepted submit(
            String userId,
            String conversationId,
            String message,
            String timezone,
            String idempotencyKey,
            String traceId) {
        ConversationSnapshot conversation = ensureConversation(userId, conversationId);
        ZoneId zoneId = parseTimezone(timezone == null || timezone.isBlank() ? conversation.timezone() : timezone);
        String effectiveMessage = preferenceService.getIfAvailable() == null
                ? message
                : preferenceService.getObject().expandLocationAliases(userId, message);
        String scopeKey = userId + ":task:" + conversationId + ":" + idempotencyKey;
        String fingerprint = sha256(effectiveMessage + "\n" + zoneId);
        AgentTaskSnapshot task;
        synchronized (taskRequests) {
            IdempotencyEntry existing = taskRequests.get(scopeKey);
            if (existing != null) {
                ensureSame(existing, fingerprint);
                AgentTaskSnapshot original = store.requireTask(existing.resourceId());
                return accepted(original.taskId(), original.status());
            }

            Optional<AgentTaskReplay> durableReplay = taskPersistence.findByIdempotencyKey(
                    userId,
                    conversationId,
                    idempotencyKey);
            if (durableReplay.isPresent()) {
                ensureSame(durableReplay.get().requestHash(), fingerprint);
                AgentTaskSnapshot recovered = durableReplay.get().snapshot();
                store.restoreTask(recovered);
                taskOwners.put(recovered.taskId(), userId);
                taskCache.putIdempotency(userId, conversationId, idempotencyKey, fingerprint, recovered.taskId());
                taskCache.putSnapshot(recovered);
                taskRequests.put(scopeKey, new IdempotencyEntry(fingerprint, recovered.taskId()));
                return accepted(recovered.taskId(), recovered.status());
            }

            if (!taskPersistence.isAvailable()) {
                Optional<AgentTaskReplay> cachedReplay = taskCache.findIdempotency(
                        userId, conversationId, idempotencyKey);
                if (cachedReplay.isPresent()) {
                    ensureSame(cachedReplay.get().requestHash(), fingerprint);
                    AgentTaskSnapshot recovered = cachedReplay.get().snapshot();
                    store.restoreTask(recovered);
                    taskOwners.put(recovered.taskId(), userId);
                    taskRequests.put(scopeKey, new IdempotencyEntry(fingerprint, recovered.taskId()));
                    return accepted(recovered.taskId(), recovered.status());
                }
            }

            contextService.appendUserMessage(userId, conversationId, effectiveMessage);
            task = store.createTask(conversationId, effectiveMessage);
            taskPersistence.create(
                    userId,
                    conversationId,
                    idempotencyKey,
                    fingerprint,
                    task);
            taskCache.putIdempotency(userId, conversationId, idempotencyKey, fingerprint, task.taskId());
            taskCache.putSnapshot(task);
            taskRequests.put(scopeKey, new IdempotencyEntry(fingerprint, task.taskId()));
            taskOwners.put(task.taskId(), userId);
        }

        eventHub.create(task.taskId());
        audit.record("agent.request.accepted", "USER", traceId, task.taskId(), Map.of(
                "messageSha256", fingerprint,
                "messageLength", effectiveMessage.length(),
                "timezone", zoneId.getId()));
        CompletableFuture.runAsync(
                () -> process(userId, task.taskId(), effectiveMessage, zoneId, traceId),
                executor);
        return accepted(task.taskId(), "PLANNING");
    }

    public AgentTaskSnapshot getTask(String taskId) {
        return getTask(taskId, null);
    }

    public AgentTaskSnapshot getTask(String taskId, String userId) {
        assertTaskOwner(taskId, userId);
        try {
            return store.requireTask(taskId);
        } catch (AgentResourceNotFoundException exception) {
            Optional<AgentTaskSnapshot> durable = taskPersistence.isAvailable()
                    ? userId == null
                    ? taskPersistence.findById(taskId)
                    : taskPersistence.findByIdForUser(taskId, userId)
                    : taskCache.getSnapshot(taskId);
            AgentTaskSnapshot recovered = durable.orElseThrow(() -> exception);
            store.restoreTask(recovered);
            if (userId != null) taskOwners.put(taskId, userId);
            return recovered;
        }
    }

    public SseEmitter events(String taskId) {
        return events(taskId, null);
    }

    public SseEmitter events(String taskId, String userId) {
        getTask(taskId, userId);
        return eventHub.subscribe(taskId);
    }

    private void process(String userId, String taskId, String message, ZoneId timezone, String traceId) {
        try {
            PromptRiskScanner.PromptRiskResult risk = promptRiskScanner.scan(message);
            if (risk.blocked()) {
                audit.record("agent.prompt_injection.blocked", "POLICY", traceId, taskId, Map.of(
                        "signals", risk.signals()));
                fail(taskId, traceId, "PROMPT_INJECTION_BLOCKED", "检测到提示注入风险，已阻止工具规划");
                return;
            }
            store.planning(taskId);
            persistTask(taskId);
            publish(taskId, traceId, "agent.task.updated", Map.of(
                    "status", "PLANNING",
                    "phase", "ANALYZING"));

            PlannerDecision decision = planner.plan(new PlanningRequest(
                    message,
                    timezone,
                    VehicleApplicationService.API_VEHICLE_ID));
            store.planned(taskId, decision);
            persistTask(taskId);
            publish(taskId, traceId, "agent.plan.created", Map.of(
                    "plannerMode", decision.mode().name(),
                    "modelName", decision.modelName(),
                    "degraded", decision.degraded(),
                    "jsonRepaired", decision.jsonRepaired(),
                    "intentSummary", decision.plan().intentSummary(),
                    "stepCount", decision.plan().toolCalls().size()));
            audit.record("agent.plan.created", "AGENT", traceId, taskId, Map.of(
                    "plannerMode", decision.mode().name(),
                    "stepCount", decision.plan().toolCalls().size(),
                    "degraded", decision.degraded()));

            store.running(taskId);
            persistTask(taskId);
            publish(taskId, traceId, "agent.task.updated", Map.of(
                    "status", "RUNNING",
                    "totalToolCalls", decision.plan().toolCalls().size()));

            List<ToolExecutionResult> results = new ArrayList<>();
            for (int index = 0; index < decision.plan().toolCalls().size(); index++) {
                ModelToolCall call = decision.plan().toolCalls().get(index);
                String callId = UUID.randomUUID().toString();
                publish(taskId, traceId, "tool.call.started", Map.of(
                        "callId", callId,
                        "tool", call.toolName(),
                        "stepIndex", index,
                        "attempt", 1));
                ToolExecutionResult result = toolRuntime.execute(
                        call.toolName(),
                        call.arguments(),
                        new ToolExecutionContext(userId, taskId, traceId));
                results.add(result);
                store.addToolCall(taskId, snapshot(result));
                persistTask(taskId);
                Map<String, Object> completedData = new LinkedHashMap<>();
                completedData.put("callId", callId);
                completedData.put("executionId", result.executionId());
                completedData.put("tool", result.toolName());
                completedData.put("status", result.success() ? "SUCCEEDED" : "FAILED");
                completedData.put("attempts", result.attempts());
                completedData.put("durationMs", result.durationMs());
                if (result.success()) {
                    completedData.put("result", result.result());
                } else {
                    completedData.put("errorCode", result.errorCode());
                    completedData.put("errorMessage", result.errorMessage());
                }
                publish(taskId, traceId, "tool.call.completed", completedData);
                audit.record("tool.call.completed", "TOOL_RUNTIME", traceId, taskId, Map.of(
                        "tool", result.toolName(),
                        "status", result.success() ? "SUCCEEDED" : "FAILED",
                        "attempts", result.attempts(),
                        "durationMs", result.durationMs(),
                        "errorCode", result.errorCode() == null ? "" : result.errorCode()));
            }

            long successCount = results.stream().filter(ToolExecutionResult::success).count();
            String status = results.isEmpty() || successCount == results.size()
                    ? "SUCCEEDED"
                    : successCount == 0 ? "FAILED" : "PARTIAL_SUCCESS";
            String response = composeResponse(decision, results);
            store.complete(taskId, status, response);
            persistTask(taskId);
            publish(taskId, traceId, "agent.response.ready", Map.of(
                    "status", status,
                    "response", response,
                    "plannerMode", decision.mode().name(),
                    "degraded", decision.degraded()));
            publish(taskId, traceId, "stream.complete", Map.of("finalStatus", status));
            audit.record("agent.response.ready", "AGENT", traceId, taskId, Map.of(
                    "status", status,
                    "toolCount", results.size()));
        } catch (AgentModelUnavailableException exception) {
            fail(taskId, traceId, "MODEL_UNAVAILABLE", exception.getMessage());
        } catch (InvalidModelOutputException exception) {
            fail(taskId, traceId, "PLAN_INVALID", exception.getMessage());
        } catch (Exception exception) {
            fail(taskId, traceId, "AGENT_WORKFLOW_FAILED", "Agent 处理失败，请根据 traceId 检查日志");
        } finally {
            eventHub.complete(taskId);
        }
    }

    private void fail(String taskId, String traceId, String code, String message) {
        store.complete(taskId, code, message);
        persistTask(taskId);
        publish(taskId, traceId, "stream.error", Map.of(
                "code", code,
                "message", message,
                "recoverable", "MODEL_UNAVAILABLE".equals(code)));
        publish(taskId, traceId, "agent.response.ready", Map.of(
                "status", code,
                "response", message,
                "degraded", true));
        publish(taskId, traceId, "stream.complete", Map.of("finalStatus", code));
        audit.record("agent.workflow.failed", "AGENT", traceId, taskId, Map.of(
                "code", code,
                "message", message));
    }

    private String composeResponse(PlannerDecision decision, List<ToolExecutionResult> results) {
        List<String> facts = new ArrayList<>();
        for (ToolExecutionResult result : results) {
            if (!result.success()) {
                facts.add(result.toolName() + " 查询失败（" + result.errorCode() + "）");
            } else if (result.result() instanceof VehicleStatus vehicle) {
                facts.add("车辆电量 %.0f%%，预计续航 %.0f km".formatted(
                        vehicle.batteryPercent(), vehicle.estimatedRangeKm()));
            } else if (result.result() instanceof WeatherForecast weather) {
                facts.add("%s %s，%d～%d°C（%s）".formatted(
                        weather.location(),
                        weather.condition(),
                        weather.minimumCelsius(),
                        weather.maximumCelsius(),
                        weather.sourceMode()));
            } else if (result.result() instanceof RouteSummary route) {
                facts.add("%s到%s约 %.1f km / %d 分钟（%s）".formatted(
                        route.origin(),
                        route.destination(),
                        route.distanceKm(),
                        route.durationMinutes(),
                        route.sourceMode()));
            }
        }
        String prefix = decision.mode() == AgentMode.RULE_STUB
                ? "规则演示模式已完成只读查询："
                : "模型已完成只读工具选择与查询：";
        String detail = facts.isEmpty() ? "没有需要调用的只读工具。" : String.join("；", facts) + "。";
        return prefix + detail + " " + decision.plan().assistantMessage();
    }

    private AgentToolCallSnapshot snapshot(ToolExecutionResult result) {
        return new AgentToolCallSnapshot(
                result.executionId(),
                result.toolName(),
                result.toolVersion(),
                result.success() ? "SUCCEEDED" : "FAILED",
                result.attempts(),
                result.durationMs(),
                result.result(),
                result.errorCode(),
                result.errorMessage(),
                result.traceId());
    }

    private void publish(String taskId, String traceId, String type, Map<String, Object> data) {
        eventHub.publish(taskId, traceId, type, data);
    }

    private ConversationSnapshot ensureConversation(String userId, String conversationId) {
        try {
            return store.requireConversation(conversationId);
        } catch (AgentResourceNotFoundException exception) {
            ConversationContextSnapshot recovered = contextService.recover(userId, conversationId)
                    .orElseThrow(() -> exception);
            store.restoreConversation(new ConversationSnapshot(
                    recovered.conversationId(),
                    recovered.title(),
                    recovered.status(),
                    recovered.timezone(),
                    recovered.createdAt()));
            return store.requireConversation(conversationId);
        }
    }

    private void persistTask(String taskId) {
        AgentTaskSnapshot snapshot = store.requireTask(taskId);
        taskPersistence.save(snapshot);
        taskCache.putSnapshot(snapshot);
    }

    private void assertTaskOwner(String taskId, String userId) {
        if (userId == null || userId.isBlank()) return;
        String owner = taskOwners.get(taskId);
        if (owner != null && !owner.equals(userId)) {
            throw new AgentResourceNotFoundException("Agent 任务", taskId);
        }
        if (owner == null && taskPersistence.isAvailable()
                && taskPersistence.findByIdForUser(taskId, userId).isEmpty()) {
            throw new AgentResourceNotFoundException("Agent 任务", taskId);
        }
    }

    private AgentTaskAccepted accepted(String taskId, String status) {
        return new AgentTaskAccepted(taskId, status, "/api/v1/agent-tasks/" + taskId + "/events");
    }

    private ZoneId parseTimezone(String timezone) {
        try {
            return ZoneId.of(timezone);
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("timezone 不是有效 IANA 时区", exception);
        }
    }

    private void ensureSame(IdempotencyEntry entry, String fingerprint) {
        if (!entry.fingerprint().equals(fingerprint)) {
            throw new AgentIdempotencyConflictException();
        }
    }

    private void ensureSame(String requestHash, String fingerprint) {
        if (!requestHash.equals(fingerprint)) {
            throw new AgentIdempotencyConflictException();
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private record IdempotencyEntry(String fingerprint, String resourceId) {
    }
}
