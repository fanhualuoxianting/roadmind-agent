package com.roadmind.server.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ToolRuntime {

    private static final int MAX_ARGUMENT_BYTES = 16 * 1024;
    private static final int TOOL_THREADS = 4;
    private static final int TOOL_QUEUE_CAPACITY = 64;

    private final Map<String, RoadMindTool<?, ?>> tools;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final ExecutorService executor;
    private final Clock clock;

    @Autowired
    public ToolRuntime(List<RoadMindTool<?, ?>> discoveredTools, ObjectMapper objectMapper, Validator validator) {
        this(discoveredTools, objectMapper, validator, Clock.systemUTC(), createExecutor());
    }

    ToolRuntime(
            List<RoadMindTool<?, ?>> discoveredTools,
            ObjectMapper objectMapper,
            Validator validator,
            Clock clock,
            ExecutorService executor) {
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.clock = clock;
        this.executor = executor;
        Map<String, RoadMindTool<?, ?>> registered = new LinkedHashMap<>();
        for (RoadMindTool<?, ?> tool : discoveredTools) {
            RoadMindTool<?, ?> previous = registered.putIfAbsent(tool.descriptor().name(), tool);
            if (previous != null) {
                throw new ToolRegistrationException("重复工具名称: " + tool.descriptor().name());
            }
        }
        this.tools = Map.copyOf(registered);
    }

    public List<ToolDescriptor> descriptors() {
        return tools.values().stream()
                .map(RoadMindTool::descriptor)
                .sorted(Comparator.comparing(ToolDescriptor::name))
                .toList();
    }

    public ToolDescriptor descriptor(String toolName) {
        return requireTool(toolName).descriptor();
    }

    public ToolExecutionResult execute(String toolName, JsonNode arguments, ToolExecutionContext context) {
        RoadMindTool<?, ?> tool = requireTool(toolName);
        if (arguments == null
                || !arguments.isObject()
                || arguments.toString().getBytes(StandardCharsets.UTF_8).length > MAX_ARGUMENT_BYTES) {
            throw new ToolInputValidationException("工具参数必须是大小受限的 JSON 对象", Map.of());
        }

        Object input = convertAndValidate(tool, arguments);
        ToolDescriptor descriptor = tool.descriptor();
        Instant startedAt = clock.instant();
        String executionId = UUID.randomUUID().toString();
        ToolDependencyException lastDependencyError = null;
        int attemptsUsed = 0;

        for (int attempt = 1; attempt <= descriptor.retryPolicy().maxAttempts(); attempt++) {
            attemptsUsed = attempt;
            try {
                Object output = executeOnce(tool, input, context, descriptor);
                Instant finishedAt = clock.instant();
                return new ToolExecutionResult(
                        true,
                        descriptor.name(),
                        descriptor.version(),
                        executionId,
                        output,
                        null,
                        null,
                        false,
                        attempt,
                        startedAt,
                        finishedAt,
                        Math.max(0, finishedAt.toEpochMilli() - startedAt.toEpochMilli()),
                        context.traceId());
            } catch (ToolDependencyException exception) {
                lastDependencyError = exception;
                if (!exception.retryable() || attempt == descriptor.retryPolicy().maxAttempts()) {
                    break;
                }
                sleep(descriptor.retryPolicy().backoff().toMillis());
            }
        }

        Instant finishedAt = clock.instant();
        ToolDependencyException error = lastDependencyError == null
                ? new ToolDependencyException("TOOL_EXECUTION_FAILED", "工具执行失败", false)
                : lastDependencyError;
        return new ToolExecutionResult(
                false,
                descriptor.name(),
                descriptor.version(),
                executionId,
                null,
                error.code(),
                error.getMessage(),
                error.retryable(),
                attemptsUsed,
                startedAt,
                finishedAt,
                Math.max(0, finishedAt.toEpochMilli() - startedAt.toEpochMilli()),
                context.traceId());
    }

    private RoadMindTool<?, ?> requireTool(String toolName) {
        RoadMindTool<?, ?> tool = tools.get(toolName);
        if (tool == null) {
            throw new UnknownToolException(toolName);
        }
        return tool;
    }

    private Object convertAndValidate(RoadMindTool<?, ?> tool, JsonNode arguments) {
        try {
            Object input = objectMapper.treeToValue(arguments, tool.inputType());
            Map<String, String> violations = new LinkedHashMap<>();
            for (ConstraintViolation<Object> violation : validator.validate(input)) {
                violations.putIfAbsent(violation.getPropertyPath().toString(), violation.getMessage());
            }
            if (!violations.isEmpty()) {
                throw new ToolInputValidationException("工具参数校验失败", violations);
            }
            return input;
        } catch (ToolInputValidationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ToolInputValidationException("工具参数 JSON 与输入 Schema 不匹配", Map.of());
        }
    }

    private Object executeOnce(
            RoadMindTool<?, ?> tool,
            Object input,
            ToolExecutionContext context,
            ToolDescriptor descriptor) {
        Future<Object> future;
        try {
            future = executor.submit(() -> invoke(tool, input, context));
        } catch (RejectedExecutionException exception) {
            throw new ToolDependencyException(
                    "TOOL_BUSY",
                    descriptor.name() + " 执行队列已满",
                    false,
                    exception);
        }
        try {
            return future.get(descriptor.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new ToolDependencyException("TOOL_TIMEOUT", descriptor.name() + " 调用超时", true, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ToolDependencyException("TOOL_INTERRUPTED", descriptor.name() + " 调用被中断", false, exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof ToolDependencyException dependencyException) {
                throw dependencyException;
            }
            throw new ToolDependencyException("TOOL_EXECUTION_FAILED", descriptor.name() + " 执行失败", false, cause);
        }
    }

    @SuppressWarnings("unchecked")
    private Object invoke(RoadMindTool<?, ?> tool, Object input, ToolExecutionContext context) {
        return ((RoadMindTool<Object, Object>) tool).execute(input, context);
    }

    private void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static ExecutorService createExecutor() {
        AtomicInteger threadSequence = new AtomicInteger();
        return new ThreadPoolExecutor(
                TOOL_THREADS,
                TOOL_THREADS,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(TOOL_QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "roadmind-tool-runtime-" + threadSequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
