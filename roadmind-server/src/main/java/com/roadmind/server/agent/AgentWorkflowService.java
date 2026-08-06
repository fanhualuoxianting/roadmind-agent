package com.roadmind.server.agent;

import com.roadmind.server.audit.AuditService;
import com.roadmind.server.conversation.ConversationContextService;
import com.roadmind.server.conversation.ConversationContextSnapshot;
import com.roadmind.server.preference.PreferenceService;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class AgentWorkflowService {

    private static final int WORKFLOW_THREADS = 2;
    private static final int WORKFLOW_QUEUE_CAPACITY = 64;
    private static final Set<String> IN_PROGRESS_STATUSES = Set.of("ACCEPTED", "PLANNING", "RUNNING");

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
    private final Map<String, String> conversationOwners = new ConcurrentHashMap<>();
    private final Map<String, String> taskOwners = new ConcurrentHashMap<>();

    @Autowired
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
        this(
                store,
                eventHub,
                planner,
                toolRuntime,
                preferenceService,
                contextService,
                taskPersistence,
                taskCache,
                promptRiskScanner,
                audit,
                createExecutor());
    }

    AgentWorkflowService(
            InMemoryAgentStore store,
            AgentEventHub eventHub,
            AgentPlannerRouter planner,
            ToolRuntime toolRuntime,
            ObjectProvider<PreferenceService> preferenceService,
            ConversationContextService contextService,
            AgentTaskPersistence taskPersistence,
            AgentTaskCache taskCache,
            PromptRiskScanner promptRiskScanner,
            AuditService audit,
            ExecutorService executor) {
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
        this.executor = executor;
    }

    public ConversationSnapshot createConversation(
            String userId,
            String title,
            String timezone,
            String idempotencyKey) {
        requireUserId(userId);
        parseTimezone(timezone);
        String scopeKey = userId + ":conversation:" + idempotencyKey;
        String fingerprint = sha256(title + "\n" + timezone);
        synchronized (conversationRequests) {
            IdempotencyEntry existing = conversationRequests.get(scopeKey);
            if (existing != null) {
                ensureSame(existing, fingerprint);
                return ensureConversation(userId, existing.resourceId());
            }
            ConversationSnapshot conversation = store.createConversation(title, timezone);
            contextService.created(userId, conversation);
            registerOwner(conversationOwners, conversation.conversationId(), userId, "会话");
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
        requireUserId(userId);
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
                AgentTaskSnapshot original = getTask(existing.resourceId(), userId);
                return accepted(original.taskId(), original.status());
            }

            Optional<AgentTaskReplay> durableReplay = taskPersistence.findByIdempotencyKey(
                    userId,
                    conversationId,
                    idempotencyKey);
            if (durableReplay.isPresent()) {
                ensureSame(durableReplay.get().requestHash(), fingerprint);
                AgentTaskSnapshot recovered = restoreRecoveredTask(userId, durableReplay.get().snapshot());
                taskCache.putIdempotency(userId, conversationId, idempotencyKey, fingerprint, recovered.taskId());
                taskRequests.put(scopeKey, new IdempotencyEntry(fingerprint, recovered.taskId()));
                return accepted(recovered.taskId(), recovered.status());
            }

            Optional<AgentTaskReplay> cachedReplay = taskCache.findIdempotency(
                    userId, conversationId, idempotencyKey);
            if (cachedReplay.isPresent()) {
                ensureSame(cachedReplay.get().requestHash(), fingerprint);
                AgentTaskSnapshot recovered = restoreRecoveredTask(userId, cachedReplay.get().snapshot());
                taskRequests.put(scopeKey, new IdempotencyEntry(fingerprint, recovered.taskId()));
                return accepted(recovered.taskId(), recovered.status());
            }

            contextService.appendUserMessage(userId, conversationId, effectiveMessage);
            task = store.createTask(conversationId, effectiveMessage);
            taskPersistence.create(
                    userId,
                    conversationId,
                    idempotencyKey,
                    fingerprint,
                    task);
            registerOwner(taskOwners, task.taskId(), userId, "Agent 任务");
            taskCache.putIdempotency(userId, conversationId, idempotencyKey, fingerprint, task.taskId());
            taskCache.putSnapshot(userId, task);
            taskRequests.put(scopeKey, new IdempotencyEntry(fingerprint, task.taskId()));
        }

        eventHub.create(task.taskId());
        audit.record("agent.request.accepted", "USER", traceId, task.taskId(), Map.of(
                "messageSha256", fingerprint,
                "messageLength", effectiveMessage.length(),
                "timezone", zoneId.getId()));
        try {
            executor.execute(() -> process(userId, task.taskId(), effectiveMessage, zoneId, traceId));
            return accepted(task.taskId(), "PLANNING");
        } catch (RejectedExecutionException exception) {
            fail(userId, task.taskId(), traceId, "AGENT_BUSY", "Agent 当前任务过多，请稍后重新提交");
            eventHub.complete(task.taskId());
            return accepted(task.taskId(), "AGENT_BUSY");
        }
    }

    public AgentTaskSnapshot getTask(String taskId, String userId) {
        requireUserId(userId);
        assertTaskOwner(taskId, userId);
        try {
            return store.requireTask(taskId);
        } catch (AgentResourceNotFoundException exception) {
            Optional<AgentTaskSnapshot> recovered = taskPersistence.findByIdForUser(taskId, userId);
            if (recovered.isEmpty()) {
                recovered = taskCache.getSnapshot(userId, taskId);
            }
            return restoreRecoveredTask(userId, recovered.orElseThrow(() -> exception));
        }
    }

    public SseEmitter events(String taskId, String userId) {
        AgentTaskSnapshot task = getTask(taskId, userId);
        if (!eventHub.hasChannel(taskId)) {
            if (isInProgress(task.status())) {
                throw new IllegalStateException("进行中的 Agent 任务缺少事件通道");
            }
            eventHub.restoreCompleted(task);
        }
        return eventHub.subscribe(taskId);
    }

    private void process(String userId, String taskId, String message, ZoneId timezone, String traceId) {
        try {
            PromptRiskScanner.PromptRiskResult risk = promptRiskScanner.scan(message);
            if (risk.blocked()) {
                audit.record("agent.prompt_injection.blocked", "POLICY", traceId, taskId, Map.of(
                        "signals", risk.signals()));
                fail(userId, taskId, traceId, "PROMPT_INJECTION_BLOCKED", "检测到提示注入风险，已阻止工具规划");
                return;
            }
            store.planning(taskId);
            persistTask(userId, taskId);
            publish(taskId, traceId, "agent.task.updated", Map.of(
                    "status", "PLANNING",
                    "phase", "ANALYZING"));

            PlannerDecision decision = planner.plan(new PlanningRequest(
                    message,
                    timezone,
                    VehicleApplicationService.API_VEHICLE_ID));
            store.planned(taskId, decision);
            persistTask(userId, taskId);
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
            persistTask(userId, taskId);
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
                persistTask(userId, taskId);
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
            persistTask(userId, taskId);
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
            fail(userId, taskId, traceId, "MODEL_UNAVAILABLE", exception.getMessage());
        } catch (InvalidModelOutputException exception) {
            fail(userId, taskId, traceId, "PLAN_INVALID", exception.getMessage());
        } catch (Exception exception) {
            fail(userId, taskId, traceId, "AGENT_WORKFLOW_FAILED", "Agent 处理失败，请根据 traceId 检查日志");
        } finally {
            eventHub.complete(taskId);
        }
    }

    private void fail(String userId, String taskId, String traceId, String code, String message) {
        store.complete(taskId, code, message);
        persistTask(userId, taskId);
        publish(taskId, traceId, "stream.error", Map.of(
                "code", code,
                "message", message,
                "recoverable", "MODEL_UNAVAILABLE".equals(code) || "AGENT_BUSY".equals(code)));
        publish(taskId, traceId, "agent.response.ready", Map.of(
                "status", code,
                "response", message,
                "degraded", true));
        publish(taskId, traceId, "stream.complete", Map.of("finalStatus", code));
        audit.record("agent.workflow.failed", "AGENT", traceId, taskId, Map.of(
                "code", code,
                "message", message));
    }

    private AgentTaskSnapshot restoreRecoveredTask(String userId, AgentTaskSnapshot snapshot) {
        registerOwner(taskOwners, snapshot.taskId(), userId, "Agent 任务");
        store.restoreTask(snapshot);
        if (isInProgress(snapshot.status())) {
            store.complete(
                    snapshot.taskId(),
                    "AGENT_RESTARTED",
                    "服务重启中断了未完成的 Agent 任务，请重新提交");
            persistTask(userId, snapshot.taskId());
        } else {
            taskCache.putSnapshot(userId, snapshot);
        }
        return store.requireTask(snapshot.taskId());
    }

    private boolean isInProgress(String status) {
        return status != null && IN_PROGRESS_STATUSES.contains(status);
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
        String owner = conversationOwners.get(conversationId);
        if (owner != null) {
            if (!owner.equals(userId)) {
                throw new AgentResourceNotFoundException("会话", conversationId);
            }
            return store.requireConversation(conversationId);
        }

        ConversationContextSnapshot recovered = contextService.recover(userId, conversationId)
                .orElseThrow(() -> new AgentResourceNotFoundException("会话", conversationId));
        store.restoreConversation(new ConversationSnapshot(
                recovered.conversationId(),
                recovered.title(),
                recovered.status(),
                recovered.timezone(),
                recovered.createdAt()));
        registerOwner(conversationOwners, conversationId, userId, "会话");
        return store.requireConversation(conversationId);
    }

    private void persistTask(String userId, String taskId) {
        assertKnownOwner(taskOwners, taskId, userId, "Agent 任务");
        AgentTaskSnapshot snapshot = store.requireTask(taskId);
        taskPersistence.save(snapshot);
        taskCache.putSnapshot(userId, snapshot);
    }

    private void assertTaskOwner(String taskId, String userId) {
        String owner = taskOwners.get(taskId);
        if (owner != null) {
            if (!owner.equals(userId)) {
                throw new AgentResourceNotFoundException("Agent 任务", taskId);
            }
            return;
        }
        if (taskPersistence.findByIdForUser(taskId, userId).isPresent()) {
            registerOwner(taskOwners, taskId, userId, "Agent 任务");
            return;
        }
        if (taskCache.getSnapshot(userId, taskId).isEmpty()) {
            throw new AgentResourceNotFoundException("Agent 任务", taskId);
        }
        registerOwner(taskOwners, taskId, userId, "Agent 任务");
    }

    private void requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
    }

    private void registerOwner(
            Map<String, String> owners,
            String resourceId,
            String userId,
            String resourceName) {
        String existing = owners.putIfAbsent(resourceId, userId);
        if (existing != null && !existing.equals(userId)) {
            throw new AgentResourceNotFoundException(resourceName, resourceId);
        }
    }

    private void assertKnownOwner(
            Map<String, String> owners,
            String resourceId,
            String userId,
            String resourceName) {
        String owner = owners.get(resourceId);
        if (owner == null || !owner.equals(userId)) {
            throw new AgentResourceNotFoundException(resourceName, resourceId);
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

    private static ExecutorService createExecutor() {
        AtomicInteger threadSequence = new AtomicInteger();
        return new ThreadPoolExecutor(
                WORKFLOW_THREADS,
                WORKFLOW_THREADS,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(WORKFLOW_QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "roadmind-agent-workflow-" + threadSequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private record IdempotencyEntry(String fingerprint, String resourceId) {
    }
}
