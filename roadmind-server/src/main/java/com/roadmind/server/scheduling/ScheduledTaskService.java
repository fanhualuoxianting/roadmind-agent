package com.roadmind.server.scheduling;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.roadmind.server.home.HomeDeviceService;
import com.roadmind.server.workflow.CoreWorkflowModels.DeferredActionAuthorization;
import com.roadmind.server.workflow.CoreWorkflowService;
import com.roadmind.server.workflow.DeferredWorkflowAuthorizationValidator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class ScheduledTaskService {

    private static final String REMINDER = "REMINDER";
    private static final Duration LEASE_DURATION = Duration.ofSeconds(30);

    private final ObjectProvider<ScheduledTaskRepository> repositoryProvider;
    private final ObjectMapper objectMapper;
    private final HomeDeviceService homeDevices;
    private final CoreWorkflowService workflowService;
    private final DeferredWorkflowAuthorizationValidator workflowAuthorizationValidator;
    private final Clock clock = Clock.systemUTC();
    private final String defaultWorkerId = "server-" + UUID.randomUUID();

    public ScheduledTaskService(
            ObjectProvider<ScheduledTaskRepository> repositoryProvider,
            ObjectMapper objectMapper) {
        this(repositoryProvider, objectMapper, null, null, null);
    }

    public ScheduledTaskService(
            ObjectProvider<ScheduledTaskRepository> repositoryProvider,
            ObjectMapper objectMapper,
            HomeDeviceService homeDevices,
            CoreWorkflowService workflowService) {
        this(repositoryProvider, objectMapper, homeDevices, workflowService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ScheduledTaskService(
            ObjectProvider<ScheduledTaskRepository> repositoryProvider,
            ObjectMapper objectMapper,
            HomeDeviceService homeDevices,
            CoreWorkflowService workflowService,
            DeferredWorkflowAuthorizationValidator workflowAuthorizationValidator) {
        this.repositoryProvider = repositoryProvider;
        this.objectMapper = objectMapper;
        this.homeDevices = homeDevices;
        this.workflowService = workflowService;
        this.workflowAuthorizationValidator = workflowAuthorizationValidator;
    }

    public ScheduledTaskSnapshot createReminder(
            String username,
            String message,
            Instant executeAt,
            String timezone,
            String idempotencyKey) {
        if (executeAt == null || !executeAt.isAfter(clock.instant())) {
            throw new IllegalArgumentException("executeAt 必须是未来的 UTC 时间");
        }
        ZoneId zoneId = parseTimezone(timezone);
        executeAt = executeAt.truncatedTo(ChronoUnit.MILLIS);
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("提醒内容不能为空");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key 不能为空");
        }

        ScheduledTaskRepository repository = repository();
        long userId = repository.requireUserId(username);
        ObjectNode payload = objectMapper.createObjectNode()
                .put("kind", "REMINDER")
                .put("message", message);
        String payloadJson = writeJson(payload);
        String payloadHash = sha256(payloadJson);

        Optional<ScheduledTaskSnapshot> existing = repository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            ensureSame(existing.get(), userId, payloadHash, executeAt, zoneId.getId());
            return existing.get();
        }

        try {
            return repository.insert(
                    userId,
                    REMINDER,
                    payloadJson,
                    payloadHash,
                    idempotencyKey,
                    executeAt,
                    zoneId.getId(),
                    clock.instant());
        } catch (DuplicateKeyException exception) {
            ScheduledTaskSnapshot raced = repository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> exception);
            ensureSame(raced, userId, payloadHash, executeAt, zoneId.getId());
            return raced;
        }
    }

    public List<ScheduledTaskSnapshot> list(String username, String status, int limit) {
        ScheduledTaskRepository repository = repository();
        String normalized = status == null || status.isBlank()
                ? null
                : status.toUpperCase(Locale.ROOT);
        if (normalized != null && !List.of(
                "PENDING", "CLAIMED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED", "EXPIRED")
                .contains(normalized)) {
            throw new IllegalArgumentException("不支持的定时任务状态: " + status);
        }
        return repository.findForUser(repository.requireUserId(username), normalized, limit);
    }

    public ScheduledTaskSnapshot createAuthorizedHomeControl(
            String username,
            DeferredActionAuthorization authorization,
            Instant executeAt,
            String timezone,
            String idempotencyKey) {
        if (!"home.set_light".equals(authorization.toolName())) {
            throw new IllegalArgumentException("当前只允许延后已授权的 home.set_light");
        }
        if (executeAt == null || !executeAt.isAfter(clock.instant())) {
            throw new IllegalArgumentException("executeAt 必须是未来的 UTC 时间");
        }
        ZoneId zoneId = parseTimezone(timezone);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key 不能为空");
        }
        long userId = repository().requireUserId(username);
        ObjectNode payload = objectMapper.createObjectNode()
                .put("kind", "HOME_CONTROL")
                .put("deviceId", String.valueOf(authorization.arguments().get("deviceId")))
                .put("on", Boolean.parseBoolean(String.valueOf(authorization.arguments().get("on"))))
                .put("authorizationWorkflowId", authorization.workflowId())
                .put("authorizationStepId", authorization.stepId())
                .put("authorizationConfirmationId", authorization.confirmationId())
                .put("authorizationPlanVersion", authorization.planVersion())
                .put("authorizationPayloadHash", authorization.payloadHash());
        String payloadJson = writeJson(payload);
        String payloadHash = sha256(payloadJson);
        executeAt = executeAt.truncatedTo(ChronoUnit.MILLIS);
        Optional<ScheduledTaskSnapshot> existing = repository().findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            ensureSame(existing.get(), userId, payloadHash, executeAt, zoneId.getId());
            return existing.get();
        }
        try {
            return repository().insert(
                    userId,
                    "HOME_CONTROL",
                    payloadJson,
                    payloadHash,
                    idempotencyKey,
                    executeAt,
                    zoneId.getId(),
                    clock.instant(),
                    authorization.workflowId(),
                    authorization.stepId(),
                    authorization.confirmationId(),
                    authorization.planVersion(),
                    authorization.payloadHash());
        } catch (DuplicateKeyException exception) {
            ScheduledTaskSnapshot raced = repository().findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> exception);
            ensureSame(raced, userId, payloadHash, executeAt, zoneId.getId());
            return raced;
        }
    }

    public ScheduledTaskSnapshot get(String username, long id) {
        ScheduledTaskRepository repository = repository();
        return repository.findByIdForUser(id, repository.requireUserId(username))
                .orElseThrow(() -> new ScheduledTaskNotFoundException(id));
    }

    public ScheduledTaskSnapshot cancel(String username, long id, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key 不能为空");
        }
        ScheduledTaskRepository repository = repository();
        long userId = repository.requireUserId(username);
        ScheduledTaskSnapshot current = repository.findByIdForUser(id, userId)
                .orElseThrow(() -> new ScheduledTaskNotFoundException(id));
        if ("CANCELLED".equals(current.status())) {
            return current;
        }
        if (!"PENDING".equals(current.status())) {
            throw new ScheduledTaskStateConflictException(id, current.status());
        }
        repository.cancel(id, userId, clock.instant());
        return repository.findByIdForUser(id, userId)
                .orElseThrow(() -> new ScheduledTaskNotFoundException(id));
    }

    public List<ScheduledTaskSnapshot> pollDueTasks() {
        return pollDueTasks(defaultWorkerId, clock.instant());
    }

    List<ScheduledTaskSnapshot> pollDueTasks(String workerId, Instant now) {
        ScheduledTaskRepository repository = repositoryProvider.getIfAvailable();
        if (repository == null || !repository.isAvailable()) {
            return List.of();
        }
        List<ScheduledTaskSnapshot> claimed = repository.claimDueTasks(
                workerId, now, now.plus(LEASE_DURATION), 20);
        for (ScheduledTaskSnapshot task : claimed) {
            if (!repository.markRunning(task.id(), workerId, now)) {
                continue;
            }
            try {
                execute(task);
                repository.markSucceeded(task.id(), workerId, clock.instant());
            } catch (Exception exception) {
                repository.markFailed(task.id(), workerId, "TASK_EXECUTION_FAILED", clock.instant());
            }
        }
        return claimed;
    }

    public void recoverExpiredLeases() {
        ScheduledTaskRepository repository = repositoryProvider.getIfAvailable();
        if (repository != null && repository.isAvailable()) {
            repository.recoverExpiredLeases(clock.instant());
        }
    }

    private void execute(ScheduledTaskSnapshot task) {
        if (REMINDER.equals(task.taskType())) {
            if (task.payload() == null || !task.payload().hasNonNull("message")) {
                throw new IllegalArgumentException("提醒任务缺少 message");
            }
            return;
        }
        if ("HOME_CONTROL".equals(task.taskType())) {
            if (homeDevices == null
                    || workflowService == null
                    || workflowAuthorizationValidator == null
                    || task.authorizationWorkflowId() == null
                    || task.authorizationStepId() == null
                    || task.authorizationConfirmationId() == null
                    || task.authorizationPlanVersion() == null
                    || task.authorizationPayloadHash() == null) {
                throw new IllegalStateException("家居任务缺少原始授权引用");
            }
            DeferredActionAuthorization authorization = workflowAuthorizationValidator.validate(
                    task.authorizationWorkflowId(),
                    task.authorizationStepId(),
                    task.authorizationConfirmationId(),
                    task.authorizationPlanVersion(),
                    task.authorizationPayloadHash());
            String deviceId = task.payload().path("deviceId").asText("");
            boolean on = task.payload().path("on").asBoolean();
            String authorizedDeviceId = String.valueOf(authorization.arguments().get("deviceId"));
            boolean authorizedOn = Boolean.parseBoolean(
                    String.valueOf(authorization.arguments().get("on")));
            if (!deviceId.equals(authorizedDeviceId) || on != authorizedOn) {
                throw new IllegalStateException("家居任务 payload 与原始授权不一致");
            }

            homeDevices.setLight(
                    deviceId,
                    on,
                    task.authorizationWorkflowId() + "/" + task.authorizationConfirmationId());
            workflowService.markDeferredActionSucceededAuthorized(
                    task.authorizationWorkflowId(),
                    task.authorizationStepId(),
                    task.authorizationConfirmationId(),
                    task.authorizationPlanVersion(),
                    task.authorizationPayloadHash(),
                    String.valueOf(task.id()));
            return;
        }
        throw new IllegalStateException("定时任务类型尚未接入执行器: " + task.taskType());
    }

    private void ensureSame(
            ScheduledTaskSnapshot existing,
            long userId,
            String payloadHash,
            Instant executeAt,
            String timezone) {
        // The task's owner is loaded by the repository only when needed. A key collision across
        // users is still treated as a conflict and never returned to the caller.
        if (!existing.payloadHash().equals(payloadHash)
                || !existing.executeAt().equals(executeAt)
                || !existing.timezone().equals(timezone)) {
            throw new ScheduledTaskIdempotencyConflictException();
        }
        Optional<ScheduledTaskSnapshot> owned = repository().findByIdForUser(existing.id(), userId);
        if (owned.isEmpty()) {
            throw new ScheduledTaskIdempotencyConflictException();
        }
    }

    private ZoneId parseTimezone(String timezone) {
        try {
            return ZoneId.of(timezone);
        } catch (Exception exception) {
            throw new IllegalArgumentException("timezone 不是有效 IANA 时区", exception);
        }
    }

    private ScheduledTaskRepository repository() {
        ScheduledTaskRepository repository = repositoryProvider.getIfAvailable();
        if (repository == null) {
            throw new IllegalStateException("定时任务持久化未启用：数据库连接不可用");
        }
        return repository;
    }

    private String writeJson(ObjectNode payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("定时任务 payload 无法编码为 JSON", exception);
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
}
