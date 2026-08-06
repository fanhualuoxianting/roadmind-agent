package com.roadmind.server.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.constraints.NotBlank;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ToolRuntimeTest {

    private ToolRuntime runtime;

    @AfterEach
    void tearDown() {
        if (runtime != null) {
            runtime.shutdown();
        }
    }

    @Test
    void rejectsDuplicateAndUnknownTools() {
        TestTool first = new TestTool("demo.read", RetryPolicy.none(), Duration.ofSeconds(1), input -> input.value());
        TestTool duplicate = new TestTool("demo.read", RetryPolicy.none(), Duration.ofSeconds(1), input -> input.value());

        assertThatThrownBy(() -> createRuntime(List.of(first, duplicate)))
                .isInstanceOf(ToolRegistrationException.class)
                .hasMessageContaining("重复工具名称");

        runtime = createRuntime(List.of(first));
        assertThatThrownBy(() -> runtime.descriptor("missing.read"))
                .isInstanceOf(UnknownToolException.class);
    }

    @Test
    void modelGeneratedArgumentsCannotBypassStrictSchemaOrBeanValidation() throws Exception {
        runtime = createRuntime(List.of(new TestTool(
                "demo.read", RetryPolicy.none(), Duration.ofSeconds(1), input -> input.value())));

        assertThatThrownBy(() -> runtime.execute(
                "demo.read",
                mapper().readTree("{\"value\":\"ok\",\"unexpected\":true}"),
                context()))
                .isInstanceOf(ToolInputValidationException.class);

        assertThatThrownBy(() -> runtime.execute(
                "demo.read",
                mapper().readTree("{\"value\":\"\"}"),
                context()))
                .isInstanceOf(ToolInputValidationException.class)
                .satisfies(error -> assertThat(((ToolInputValidationException) error).fields())
                        .containsKey("value"));

        assertThatThrownBy(() -> runtime.execute(
                "demo.read",
                mapper().createObjectNode().put("value", "路".repeat(6_000)),
                context()))
                .isInstanceOf(ToolInputValidationException.class)
                .hasMessageContaining("大小受限");
    }

    @Test
    void retriesOnlyRetryableDependencyFailureOnce() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        runtime = createRuntime(List.of(new TestTool(
                "demo.read",
                RetryPolicy.retryOnce(Duration.ZERO),
                Duration.ofSeconds(1),
                input -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new ToolDependencyException("TEMPORARY", "temporary", true);
                    }
                    return input.value();
                })));

        ToolExecutionResult result = runtime.execute(
                "demo.read", mapper().readTree("{\"value\":\"done\"}"), context());

        assertThat(result.success()).isTrue();
        assertThat(result.attempts()).isEqualTo(2);
        assertThat(result.result()).isEqualTo("done");
    }

    @Test
    void reportsActualAttemptCountForNonRetryableFailure() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        runtime = createRuntime(List.of(new TestTool(
                "demo.read",
                RetryPolicy.retryOnce(Duration.ZERO),
                Duration.ofSeconds(1),
                input -> {
                    invocations.incrementAndGet();
                    throw new ToolDependencyException("PERMANENT", "permanent", false);
                })));

        ToolExecutionResult result = runtime.execute(
                "demo.read", mapper().readTree("{\"value\":\"done\"}"), context());

        assertThat(result.success()).isFalse();
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(invocations).hasValue(1);
    }

    @Test
    void mapsTimeoutWithoutLeakingException() throws Exception {
        runtime = createRuntime(List.of(new TestTool(
                "demo.read",
                RetryPolicy.none(),
                Duration.ofMillis(20),
                input -> {
                    Thread.sleep(200);
                    return input.value();
                })));

        ToolExecutionResult result = runtime.execute(
                "demo.read", mapper().readTree("{\"value\":\"slow\"}"), context());

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("TOOL_TIMEOUT");
        assertThat(result.retryable()).isTrue();
    }

    @Test
    void mapsSaturatedExecutorToStableToolBusyResult() throws Exception {
        runtime = new ToolRuntime(
                List.of(new TestTool(
                        "demo.read",
                        RetryPolicy.retryOnce(Duration.ZERO),
                        Duration.ofSeconds(1),
                        Input::value)),
                mapper(),
                Validation.buildDefaultValidatorFactory().getValidator(),
                Clock.systemUTC(),
                new RejectingExecutorService());

        ToolExecutionResult result = runtime.execute(
                "demo.read", mapper().readTree("{\"value\":\"queued\"}"), context());

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("TOOL_BUSY");
        assertThat(result.retryable()).isFalse();
        assertThat(result.attempts()).isEqualTo(1);
    }

    private ToolRuntime createRuntime(List<RoadMindTool<?, ?>> tools) {
        return new ToolRuntime(
                tools,
                mapper(),
                Validation.buildDefaultValidatorFactory().getValidator(),
                Clock.systemUTC(),
                Executors.newSingleThreadExecutor());
    }

    private ObjectMapper mapper() {
        return new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    private ToolExecutionContext context() {
        return new ToolExecutionContext("demo", "task-1", "trace-1");
    }

    private record Input(@NotBlank String value) {
    }

    @FunctionalInterface
    private interface Behavior {
        String apply(Input input) throws Exception;
    }

    private static final class TestTool implements RoadMindTool<Input, String> {
        private final ToolDescriptor descriptor;
        private final Behavior behavior;

        private TestTool(String name, RetryPolicy retryPolicy, Duration timeout, Behavior behavior) {
            this.descriptor = new ToolDescriptor(
                    name,
                    "1.0.0",
                    "test",
                    ToolRiskLevel.READ_ONLY,
                    timeout,
                    retryPolicy,
                    true,
                    "test-schema");
            this.behavior = behavior;
        }

        @Override
        public ToolDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public Class<Input> inputType() {
            return Input.class;
        }

        @Override
        public String execute(Input input, ToolExecutionContext context) {
            try {
                return behavior.apply(input);
            } catch (ToolDependencyException exception) {
                throw exception;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ToolDependencyException("INTERRUPTED", "interrupted", false, exception);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }
    }

    private static final class RejectingExecutorService extends AbstractExecutorService {
        private boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }

        @Override
        public void execute(Runnable command) {
            throw new RejectedExecutionException("queue full");
        }
    }
}
