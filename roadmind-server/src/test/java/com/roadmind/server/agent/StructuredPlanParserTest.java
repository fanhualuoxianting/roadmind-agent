package com.roadmind.server.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class StructuredPlanParserTest {

    private final StructuredPlanParser parser = new StructuredPlanParser(
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));

    @Test
    void parsesStrictPlanWithoutRepair() {
        ParsedModelPlan parsed = parser.parse("""
                {"intentSummary":"查询车辆","assistantMessage":"开始查询",\
                "toolCalls":[{"toolName":"vehicle.get_status","arguments":{"vehicleId":"1"}}]}
                """);

        assertThat(parsed.repaired()).isFalse();
        assertThat(parsed.plan().toolCalls()).hasSize(1);
    }

    @Test
    void performsOnlyOneBoundedFenceAndTrailingCommaRepair() {
        ParsedModelPlan parsed = parser.parse("""
                ```json
                {"intentSummary":"查询天气","assistantMessage":"开始查询",\
                "toolCalls":[{"toolName":"weather.get_forecast","arguments":{"location":"南京"}},],}
                ```
                """);

        assertThat(parsed.repaired()).isTrue();
        assertThat(parsed.plan().toolCalls()).singleElement()
                .extracting(ModelToolCall::toolName)
                .isEqualTo("weather.get_forecast");
    }

    @Test
    void rejectsUnknownFieldsAfterRepair() {
        assertThatThrownBy(() -> parser.parse("""
                {"intentSummary":"x","assistantMessage":"y","toolCalls":[],"executeNow":true}
                """))
                .isInstanceOf(InvalidModelOutputException.class);
    }

    @Test
    void extractsJsonAfterModelThinkingBlock() {
        ParsedModelPlan parsed = parser.parse("""
                <think>先分析用户请求，再决定只读工具。</think>
                {"intentSummary":"查询车辆","assistantMessage":"开始查询",\
                "toolCalls":[{"toolName":"vehicle.get_status","arguments":{"vehicleId":"1"}}]}
                """);

        assertThat(parsed.repaired()).isTrue();
        assertThat(parsed.plan().toolCalls()).singleElement()
                .extracting(ModelToolCall::toolName)
                .isEqualTo("vehicle.get_status");
    }
}
