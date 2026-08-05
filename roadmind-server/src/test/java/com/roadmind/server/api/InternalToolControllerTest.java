package com.roadmind.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roadmind.server.tool.RetryPolicy;
import com.roadmind.server.tool.ToolDescriptor;
import com.roadmind.server.tool.ToolExecutionContext;
import com.roadmind.server.tool.ToolExecutionResult;
import com.roadmind.server.tool.ToolRiskLevel;
import com.roadmind.server.tool.ToolRuntime;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class InternalToolControllerTest {

    @Test
    void disabledOrMismatchedTokenIsRejectedBeforeToolLookup() {
        ToolRuntime tools = mock(ToolRuntime.class);
        InternalToolController disabled = new InternalToolController(tools, "disabled");
        InternalToolController enabled = new InternalToolController(tools, "local-secret-token");
        JsonNode arguments = new ObjectMapper().createObjectNode();

        assertThat(disabled.validInternalToken("disabled")).isFalse();
        assertThat(enabled.validInternalToken("local-secret-token")).isTrue();
        assertThat(enabled.validInternalToken("local-secret-tokeN")).isFalse();
        assertThat(enabled.validInternalToken(null)).isFalse();

        assertThatThrownBy(() -> enabled.execute(
                "demo.read", "wrong-token", "request-1", arguments))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void validTokenExecutesOnlyReadOnlyTool() {
        ToolRuntime tools = mock(ToolRuntime.class);
        InternalToolController controller = new InternalToolController(tools, "local-secret-token");
        JsonNode arguments = new ObjectMapper().createObjectNode();
        ToolExecutionResult expected = mock(ToolExecutionResult.class);
        when(tools.descriptor("demo.read")).thenReturn(descriptor(ToolRiskLevel.READ_ONLY));
        when(tools.execute(eq("demo.read"), same(arguments), any(ToolExecutionContext.class)))
                .thenReturn(expected);

        ToolExecutionResult result = controller.execute(
                "demo.read", "local-secret-token", "request-2", arguments);

        assertThat(result).isSameAs(expected);
    }

    @Test
    void validTokenStillCannotExecuteWriteTool() {
        ToolRuntime tools = mock(ToolRuntime.class);
        InternalToolController controller = new InternalToolController(tools, "local-secret-token");
        when(tools.descriptor("demo.write")).thenReturn(descriptor(ToolRiskLevel.HIGH_RISK_WRITE));

        assertThatThrownBy(() -> controller.execute(
                "demo.write",
                "local-secret-token",
                "request-3",
                new ObjectMapper().createObjectNode()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    private ToolDescriptor descriptor(ToolRiskLevel riskLevel) {
        return new ToolDescriptor(
                "demo",
                "1.0.0",
                "test",
                riskLevel,
                Duration.ofSeconds(1),
                RetryPolicy.none(),
                true,
                "test-schema");
    }
}
