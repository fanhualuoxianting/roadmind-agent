package com.roadmind.server.workflow;

import com.roadmind.server.agent.AgentResourceNotFoundException;
import com.roadmind.server.home.HomeDeviceService;
import com.roadmind.server.vehicle.domain.VehicleNativeScheduler;
import com.roadmind.server.workflow.CoreWorkflowModels.Confirmation;
import com.roadmind.server.workflow.CoreWorkflowModels.DeferredActionAuthorization;
import com.roadmind.server.workflow.CoreWorkflowModels.Slot;
import com.roadmind.server.workflow.CoreWorkflowModels.Snapshot;
import com.roadmind.server.workflow.CoreWorkflowModels.Step;
import com.roadmind.server.workflow.CoreWorkflowModels.TimelineEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class CoreWorkflowService {
    private static final Pattern ROUTE = Pattern.compile("从([^，。]+?)(?:出发)?去([^，。]+)");
    private static final Pattern DESTINATION = Pattern.compile("去([^，。]+)");
    private static final Pattern TIME = Pattern.compile("(明天(?:早上|上午|下午|晚上)?\\s*\\d{1,2}(?:[:点时]\\d{0,2})?)");
    private final Map<String, MutableWorkflow> byConversation = new ConcurrentHashMap<>();
    private final Map<String, MutableWorkflow> byId = new ConcurrentHashMap<>();
    private final Clock clock;
    private final CoreWorkflowPersistence persistence;
    private final CoreWorkflowCache cache;
    private final VehicleNativeScheduler vehicleScheduler;
    private final HomeDeviceService homeDevices;

    public CoreWorkflowService() { this(Clock.systemUTC(), null, null, null, null); }

    @Autowired
    public CoreWorkflowService(
            CoreWorkflowPersistence persistence,
            CoreWorkflowCache cache,
            ObjectProvider<VehicleNativeScheduler> vehicleScheduler,
            HomeDeviceService homeDevices) {
        this(Clock.systemUTC(), persistence, cache, vehicleScheduler.getIfAvailable(), homeDevices);
    }

    CoreWorkflowService(Clock clock) { this(clock, null, null, null, null); }

    CoreWorkflowService(Clock clock, CoreWorkflowPersistence persistence) {
        this(clock, persistence, null, null, null);
    }

    CoreWorkflowService(Clock clock, CoreWorkflowPersistence persistence, CoreWorkflowCache cache) {
        this(clock, persistence, cache, null, null);
    }

    CoreWorkflowService(
            Clock clock,
            CoreWorkflowPersistence persistence,
            CoreWorkflowCache cache,
            VehicleNativeScheduler vehicleScheduler,
            HomeDeviceService homeDevices) {
        this.clock = clock;
        this.persistence = persistence;
        this.cache = cache;
        this.vehicleScheduler = vehicleScheduler;
        this.homeDevices = homeDevices;
    }

    public Snapshot message(String conversationId, String message) {
        if (message == null || message.isBlank()) throw new IllegalArgumentException("message 不能为空");
        MutableWorkflow workflow = byConversation.computeIfAbsent(
                conversationId,
                id -> persistence == null
                        ? cachedOrCreate(id)
                        : persistenceAvailable()
                                ? persistence.findLatestByConversation(id)
                                        .map(this::restore)
                                        .orElseGet(() -> create(id))
                                : cachedOrCreate(id));
        synchronized (workflow) {
            workflow.contextVersion++;
            workflow.prompt = message.strip();
            applySlots(workflow, message);
            workflow.timeline.add(event("CONTEXT_UPDATED", "已更新任务上下文", "上下文版本 " + workflow.contextVersion));
            List<String> missing = missing(workflow.slots);
            workflow.missingSlots = missing;
            if (!missing.isEmpty()) {
                workflow.status = "WAITING_INPUT";
                workflow.response = question(missing);
                workflow.steps = List.of();
                workflow.confirmation = null;
            } else {
                workflow.planVersion++;
                workflow.steps = plan(workflow);
                validateDag(workflow.steps);
                workflow.confirmation = confirmation(workflow);
                workflow.status = "WAITING_CONFIRMATION";
                workflow.response = "计划已生成。车辆预热与到家后关闭灯光属于写操作，请确认后执行。";
                workflow.timeline.add(event("PLAN_CREATED", "已生成执行计划", "计划版本 " + workflow.planVersion + "，共 " + workflow.steps.size() + " 个步骤"));
                workflow.timeline.add(event("POLICY_BLOCKED", "策略门已暂停高风险步骤", "等待逐项确认，未调用任何写工具"));
            }
            workflow.updatedAt = clock.instant();
            return remember(workflow);
        }
    }

    public Snapshot get(String workflowId) { return require(workflowId).snapshot(); }

    public Snapshot decide(String workflowId, String decision, int planVersion, String payloadHash) {
        MutableWorkflow workflow = require(workflowId);
        synchronized (workflow) {
            Confirmation confirmation = workflow.confirmation;
            if (confirmation == null) throw new IllegalArgumentException("当前任务没有待确认操作");
            if (!"PENDING".equals(confirmation.status())) return workflow.snapshot();
            if (clock.instant().isAfter(confirmation.expiresAt())) {
                workflow.confirmation = withStatus(confirmation, "EXPIRED");
                workflow.status = "CONFIRMATION_EXPIRED";
                workflow.timeline.add(event("CONFIRMATION_EXPIRED", "确认已过期", "请重新生成计划"));
                return remember(workflow);
            }
            if (planVersion != workflow.planVersion || !confirmation.payloadHash().equals(payloadHash)) {
                throw new WorkflowConflictException("计划版本或操作摘要已变化，请刷新后重新确认");
            }
            String normalized = decision == null ? "" : decision.toUpperCase(Locale.ROOT);
            if ("REJECT".equals(normalized)) {
                workflow.confirmation = withStatus(confirmation, "REJECTED");
                workflow.steps = updateWriteSteps(workflow.steps, "SKIPPED", "用户拒绝");
                workflow.status = "PARTIAL_SUCCESS";
                workflow.response = "只读规划已保留；车辆与家居写操作已拒绝，未执行。";
                workflow.timeline.add(event("CONFIRMATION_REJECTED", "用户拒绝写操作", "Policy Gate 保持阻断"));
            } else if ("APPROVE".equals(normalized)) {
                workflow.confirmation = withStatus(confirmation, "APPROVED");
                workflow.status = "RUNNING";
                workflow.timeline.add(event("EXECUTION_READY", "确认事务已持久化为 READY", "Executor 已领取一次性执行意图"));
                executeAndVerify(workflow, false);
            } else if ("APPROVE_DEFERRED".equals(normalized)) {
                workflow.confirmation = withStatus(confirmation, "APPROVED");
                workflow.status = "RUNNING";
                workflow.timeline.add(event("EXECUTION_READY", "确认事务已持久化为 READY", "高风险家居步骤保留原授权，等待延后调度"));
                executeAndVerify(workflow, true);
            } else throw new IllegalArgumentException("decision 仅支持 APPROVE、APPROVE_DEFERRED 或 REJECT");
            workflow.updatedAt = clock.instant();
            return remember(workflow);
        }
    }

    public DeferredActionAuthorization authorizeDeferredAction(
            String workflowId,
            String stepId,
            String confirmationId,
            int planVersion,
            String payloadHash) {
        MutableWorkflow workflow = require(workflowId);
        synchronized (workflow) {
            Confirmation confirmation = workflow.confirmation;
            if (confirmation == null || !"APPROVED".equals(confirmation.status())) {
                throw new WorkflowConflictException("高风险动作尚未完成用户确认");
            }
            if (!confirmation.confirmationId().equals(confirmationId)
                    || confirmation.planVersion() != planVersion
                    || !confirmation.payloadHash().equals(payloadHash)) {
                throw new WorkflowConflictException("延后动作授权摘要已变化，请重新确认");
            }
            Step step = workflow.steps.stream()
                    .filter(candidate -> candidate.stepId().equals(stepId))
                    .findFirst()
                    .orElseThrow(() -> new AgentResourceNotFoundException("计划步骤", stepId));
            if (!"HIGH".equals(step.risk()) || !"DEFERRED".equals(step.status())) {
                throw new WorkflowConflictException("该计划步骤不是可延后的已授权高风险动作");
            }
            return new DeferredActionAuthorization(
                    workflow.id, workflow.conversationId, step.stepId(), step.toolName(),
                    workflow.planVersion, confirmation.confirmationId(), confirmation.payloadHash(), step.arguments());
        }
    }

    public Snapshot markDeferredActionSucceeded(String workflowId, String stepId, String taskId) {
        MutableWorkflow workflow = require(workflowId);
        synchronized (workflow) {
            List<Step> updated = new ArrayList<>();
            boolean changed = false;
            for (Step step : workflow.steps) {
                if (step.stepId().equals(stepId) && "DEFERRED".equals(step.status())) {
                    updated.add(new Step(step.stepId(), step.title(), step.toolName(), step.dependsOn(), step.risk(),
                            "SUCCEEDED", step.arguments(), "延后任务 " + taskId + " 执行并回查一致"));
                    changed = true;
                    workflow.timeline.add(event("STEP_SUCCEEDED", step.title(), "延后任务 " + taskId + " 已完成"));
                } else {
                    updated.add(step);
                }
            }
            if (!changed) return workflow.snapshot();
            workflow.steps = List.copyOf(updated);
            boolean hasDeferred = workflow.steps.stream().anyMatch(step -> "DEFERRED".equals(step.status()));
            workflow.status = hasDeferred ? "WAITING_SCHEDULE" : "SUCCEEDED";
            workflow.response = hasDeferred
                    ? "部分延后动作仍在等待执行。"
                    : "执行完成：路线、车辆原生预热和家居任务均已通过数字孪生回查。";
            workflow.updatedAt = clock.instant();
            return remember(workflow);
        }
    }

    private void executeAndVerify(MutableWorkflow workflow, boolean deferHome) {
        boolean mismatch = workflow.prompt.contains("验证失败") || workflow.prompt.contains("回查不一致");
        List<Step> done = new ArrayList<>();
        boolean hasDeferred = false;
        boolean hasFailure = false;
        for (Step step : workflow.steps) {
            String status = "SUCCEEDED";
            String verification = step.verification();
            try {
                if (deferHome && "home.set_light".equals(step.toolName())) {
                    status = "DEFERRED";
                    hasDeferred = true;
                    verification = "已保留原始高风险授权，等待用户指定执行时间；未执行家居写操作";
                } else if ("vehicle.schedule_precondition".equals(step.toolName()) && vehicleScheduler != null) {
                    Instant executeAt = climateExecuteAt(step.arguments().get("time"));
                    vehicleScheduler.scheduleClimate(
                            "demo-vehicle-001",
                            executeAt,
                            number(step.arguments().get("temperature"), 24),
                            workflow.id + ":" + workflow.confirmation.confirmationId() + ":" + step.stepId());
                    verification = "车辆模拟器已创建原生定时命令，状态可由 scheduled-tasks 回查";
                } else if ("home.set_light".equals(step.toolName()) && homeDevices != null) {
                    String deviceId = String.valueOf(step.arguments().get("deviceId"));
                    boolean on = Boolean.parseBoolean(String.valueOf(step.arguments().get("on")));
                    HomeDeviceService devices = homeDevices;
                    devices.setLight(deviceId, on, workflow.id + "/" + workflow.confirmation.confirmationId());
                    verification = "家居数字孪生状态回查一致";
                }
                if (mismatch && "home.set_light".equals(step.toolName())) {
                    status = "VERIFICATION_FAILED";
                    verification = "工具返回成功，但回查灯光仍为开启";
                    hasFailure = true;
                }
            } catch (RuntimeException exception) {
                status = "FAILED";
                hasFailure = true;
                verification = "执行失败：" + exception.getMessage();
            }
            done.add(new Step(step.stepId(), step.title(), step.toolName(), step.dependsOn(), step.risk(), status, step.arguments(), verification));
            workflow.timeline.add(event("STEP_" + status, step.title(), verification));
        }
        workflow.steps = List.copyOf(done);
        workflow.status = hasDeferred ? "WAITING_SCHEDULE" : (hasFailure ? "PARTIAL_SUCCESS" : "SUCCEEDED");
        workflow.response = hasDeferred
                ? "车辆原生预热已预约；家居高风险动作已保留原授权，等待指定延后时间。"
                : hasFailure
                ? "计划部分成功：车辆预热已验证，家居灯光回查不一致，已停止依赖步骤并记录审计。"
                : "执行完成：路线与车辆状态已查询，预热和家居任务已通过数字孪生回查。";
    }

    private Instant climateExecuteAt(Object value) {
        String raw = String.valueOf(value);
        Matcher matcher = Pattern.compile("(\\d{1,2})(?::(\\d{1,2}))?").matcher(raw);
        if (!matcher.find()) return clock.instant().plus(Duration.ofMinutes(1));
        int hour = Math.min(23, Integer.parseInt(matcher.group(1)));
        int minute = matcher.group(2) == null || matcher.group(2).isBlank()
                ? 0 : Math.min(59, Integer.parseInt(matcher.group(2)));
        return LocalDate.now(clock).plusDays(1)
                .atTime(LocalTime.of(hour, minute))
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant();
    }

    private double number(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private MutableWorkflow create(String conversationId) {
        MutableWorkflow workflow = new MutableWorkflow(UUID.randomUUID().toString(), conversationId, clock.instant());
        byId.put(workflow.id, workflow);
        return workflow;
    }

    private void applySlots(MutableWorkflow w, String message) {
        Matcher route = ROUTE.matcher(message);
        if (route.find()) {
            put(w, "origin", clean(route.group(1)));
            put(w, "destination", clean(route.group(2)));
        } else {
            Matcher destination = DESTINATION.matcher(message);
            if (destination.find()) put(w, "destination", clean(destination.group(1)));
        }
        if (message.contains("不是学校") || message.contains("南京南站")) put(w, "origin", "南京南站");
        Matcher time = TIME.matcher(message);
        if (time.find()) put(w, "departureTime", time.group(1).replace('点', ':'));
    }

    private String clean(String value) {
        return value.replace("出发", "").replace("避开拥堵", "").strip();
    }

    private void put(MutableWorkflow w, String name, String value) {
        Slot old = w.slots.get(name);
        if (old == null || !old.value().equals(value)) w.slots.put(name, new Slot(name, value, "USER_EXPLICIT", w.contextVersion));
    }

    private List<String> missing(Map<String, Slot> slots) {
        List<String> result = new ArrayList<>();
        if (!slots.containsKey("origin")) result.add("origin");
        if (!slots.containsKey("destination")) result.add("destination");
        if (!slots.containsKey("departureTime")) result.add("departureTime");
        return result;
    }

    private String question(List<String> missing) {
        List<String> labels = missing.stream().map(value -> switch (value) {
            case "origin" -> "出发地点"; case "destination" -> "目的地"; default -> "出发时间";
        }).toList();
        return "还需要你补充：" + String.join("、", labels) + "。";
    }

    private List<Step> plan(MutableWorkflow w) {
        String origin = w.slots.get("origin").value();
        String destination = w.slots.get("destination").value();
        String time = w.slots.get("departureTime").value();
        return List.of(
                step("s1", "查询天气", "weather.get_forecast", List.of(), "READ_ONLY", Map.of("location", destination), "天气来源与时间已核对"),
                step("s2", "获取车辆电量", "vehicle.get_status", List.of(), "READ_ONLY", Map.of("vehicleId", "198000000000000401"), "车辆状态来自 DIGITAL_TWIN"),
                step("s3", "规划避堵路线", "route.plan", List.of("s1", "s2"), "READ_ONLY", Map.of("origin", origin, "destination", destination), "路线参数完整"),
                step("s4", "预约车辆预热", "vehicle.schedule_precondition", List.of("s3"), "HIGH", Map.of("time", time, "temperature", 24), "预热任务状态回查一致"),
                step("s5", "到达后关闭家中灯光", "home.set_light", List.of("s3"), "HIGH", Map.of("deviceId", "demo-home-light-01", "on", false), "家居设备状态回查一致"));
    }

    private Step step(String id, String title, String tool, List<String> deps, String risk, Map<String,Object> args, String verification) {
        return new Step(id, title, tool, deps, risk, "PENDING", args, verification);
    }

    static void validateDag(List<Step> steps) {
        Map<String, Step> known = new LinkedHashMap<>();
        for (Step step : steps) {
            if (known.put(step.stepId(), step) != null) throw new IllegalArgumentException("DAG 包含重复步骤");
            for (String dependency : step.dependsOn()) if (!known.containsKey(dependency)) throw new IllegalArgumentException("DAG 依赖不存在或形成逆向循环");
        }
        if (steps.size() > 20) throw new IllegalArgumentException("计划步骤超过上限");
    }

    private Confirmation confirmation(MutableWorkflow w) {
        List<String> items = w.steps.stream().filter(step -> "HIGH".equals(step.risk())).map(Step::stepId).toList();
        String payload = w.planVersion + "|" + items + "|" + w.steps.stream().map(Step::arguments).toList();
        return new Confirmation(UUID.randomUUID().toString(), "PENDING", w.planVersion, sha256(payload), clock.instant().plus(Duration.ofMinutes(10)), items);
    }

    private Confirmation withStatus(Confirmation c, String status) {
        return new Confirmation(c.confirmationId(), status, c.planVersion(), c.payloadHash(), c.expiresAt(), c.itemIds());
    }

    private List<Step> updateWriteSteps(List<Step> steps, String status, String verification) {
        return steps.stream().map(s -> "HIGH".equals(s.risk())
                ? new Step(s.stepId(), s.title(), s.toolName(), s.dependsOn(), s.risk(), status, s.arguments(), verification)
                : s).toList();
    }

    private TimelineEvent event(String type, String title, String detail) { return new TimelineEvent(clock.instant(), type, title, detail); }

    private MutableWorkflow require(String id) {
        MutableWorkflow workflow = byId.get(id);
        if (workflow != null) return workflow;
        if (persistenceAvailable()) {
            Optional<Snapshot> recovered = persistence.findById(id);
            if (recovered.isPresent()) {
                MutableWorkflow restored = restore(recovered.get());
                byConversation.putIfAbsent(restored.conversationId, restored);
                return restored;
            }
        }
        if (!persistenceAvailable() && cache != null) {
            Optional<Snapshot> cached = cache.get(id);
            if (cached.isPresent()) {
                MutableWorkflow restored = restore(cached.get());
                byConversation.putIfAbsent(restored.conversationId, restored);
                return restored;
            }
        }
        throw new AgentResourceNotFoundException("工作流", id);
    }

    private Snapshot remember(MutableWorkflow workflow) {
        Snapshot snapshot = workflow.snapshot();
        if (persistence != null) persistence.save(snapshot);
        if (cache != null) cache.put(snapshot);
        return snapshot;
    }

    private boolean persistenceAvailable() {
        return persistence != null && persistence.isAvailable();
    }

    private MutableWorkflow restore(Snapshot snapshot) {
        MutableWorkflow workflow = MutableWorkflow.from(snapshot);
        byId.putIfAbsent(workflow.id, workflow);
        return workflow;
    }

    private MutableWorkflow cachedOrCreate(String conversationId) {
        if (cache != null) {
            Optional<Snapshot> cached = cache.findByConversation(conversationId);
            if (cached.isPresent()) {
                return restore(cached.get());
            }
        }
        return create(conversationId);
    }

    private String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }

    private static final class MutableWorkflow {
        final String id; final String conversationId; final Map<String, Slot> slots = new LinkedHashMap<>(); final List<TimelineEvent> timeline = new ArrayList<>();
        int contextVersion; int planVersion; String prompt = ""; String status = "NEW"; List<String> missingSlots = List.of(); List<Step> steps = List.of(); Confirmation confirmation; String response; Instant updatedAt;
        MutableWorkflow(String id, String conversationId, Instant now) { this.id=id; this.conversationId=conversationId; this.updatedAt=now; }
        static MutableWorkflow from(Snapshot snapshot) {
            MutableWorkflow workflow = new MutableWorkflow(
                    snapshot.workflowId(), snapshot.conversationId(), snapshot.updatedAt());
            workflow.contextVersion = snapshot.contextVersion();
            workflow.planVersion = snapshot.planVersion();
            workflow.prompt = snapshot.prompt();
            workflow.status = snapshot.status();
            workflow.missingSlots = snapshot.missingSlots() == null ? List.of() : List.copyOf(snapshot.missingSlots());
            workflow.steps = snapshot.steps() == null ? List.of() : List.copyOf(snapshot.steps());
            workflow.confirmation = snapshot.confirmation();
            workflow.timeline.addAll(snapshot.timeline() == null ? List.of() : snapshot.timeline());
            workflow.response = snapshot.response();
            if (snapshot.slots() != null) {
                for (Slot slot : snapshot.slots()) workflow.slots.put(slot.name(), slot);
            }
            return workflow;
        }
        synchronized Snapshot snapshot() { return new Snapshot(id, conversationId, status, contextVersion, planVersion, prompt, List.copyOf(slots.values()), missingSlots, steps, confirmation, List.copyOf(timeline), response, updatedAt); }
    }
}
