package com.roadmind.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class StructuredPlanParser {

    private static final int MAX_MODEL_OUTPUT_LENGTH = 64 * 1024;

    private final ObjectMapper objectMapper;

    public StructuredPlanParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ParsedModelPlan parse(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank() || rawOutput.length() > MAX_MODEL_OUTPUT_LENGTH) {
            throw new InvalidModelOutputException("模型输出为空或超过大小限制", null);
        }
        try {
            return new ParsedModelPlan(validate(objectMapper.readValue(rawOutput, ModelToolPlan.class)), false);
        } catch (Exception firstFailure) {
            String repaired = repairOnce(rawOutput);
            try {
                return new ParsedModelPlan(validate(objectMapper.readValue(repaired, ModelToolPlan.class)), true);
            } catch (Exception secondFailure) {
                throw new InvalidModelOutputException("模型未返回合法的结构化工具计划", secondFailure);
            }
        }
    }

    private ModelToolPlan validate(ModelToolPlan plan) {
        if (plan.intentSummary() == null || plan.intentSummary().isBlank()) {
            throw new IllegalArgumentException("缺少 intentSummary");
        }
        for (ModelToolCall call : plan.toolCalls()) {
            if (call.toolName() == null || call.toolName().isBlank()
                    || call.arguments() == null || !call.arguments().isObject()) {
                throw new IllegalArgumentException("工具请求结构不完整");
            }
        }
        return plan;
    }

    private String repairOnce(String rawOutput) {
        String candidate = rawOutput.trim()
                .replaceFirst("(?s)^```(?:json)?\\s*", "")
                .replaceFirst("(?s)\\s*```$", "");
        int thinkEnd = candidate.lastIndexOf("</think>");
        if (thinkEnd >= 0) {
            candidate = candidate.substring(thinkEnd + "</think>".length()).trim();
        }
        int firstBrace = candidate.indexOf('{');
        int lastBrace = candidate.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            candidate = candidate.substring(firstBrace, lastBrace + 1);
        }
        return candidate
                .replaceAll(",\\s*}", "}")
                .replaceAll(",\\s*]", "]");
    }
}
