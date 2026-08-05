package com.roadmind.server.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Detects high-signal prompt-injection language before a user message reaches
 * the planner. Tool results remain typed data and are never appended to this
 * planner input.
 */
@Component
public class PromptRiskScanner {

    private final List<Signal> signals = List.of(
            new Signal("IGNORE_PRIOR_INSTRUCTIONS", Pattern.compile(
                    "(?:ignore|disregard|override)\\s+(?:all\\s+)?(?:previous|prior|above)\\s+(?:instructions|rules)",
                    Pattern.CASE_INSENSITIVE)),
            new Signal("SYSTEM_PROMPT_EXTRACTION", Pattern.compile(
                    "(?:reveal|show|print|leak)\\s+(?:the\\s+)?(?:system|developer)\\s+prompt"
                            + "|(?:输出|显示|泄露|告诉我)(?:所有|完整)?(?:系统|开发者)(?:提示词|提示|指令)",
                    Pattern.CASE_INSENSITIVE)),
            new Signal("ROLE_ESCALATION", Pattern.compile(
                    "(?:you\\s+are\\s+now|act\\s+as)\\s+(?:the\\s+)?(?:system|developer|root|admin)",
                    Pattern.CASE_INSENSITIVE)),
            new Signal("TOOL_POLICY_BYPASS", Pattern.compile(
                    "(?:bypass|skip|disable|绕过|跳过|关闭)\\s*(?:policy|authorization|confirmation|gate|策略|授权|确认)",
                    Pattern.CASE_INSENSITIVE)),
            new Signal("CHINESE_INSTRUCTION_OVERRIDE", Pattern.compile(
                    "(?:忽略|无视|覆盖)(?:之前|上面|先前|系统|开发者)(?:的)?(?:所有|全部)?(?:指令|规则|提示)",
                    Pattern.CASE_INSENSITIVE)),
            new Signal("SECRET_EXTRACTION", Pattern.compile(
                    "(?:输出|告诉我|泄露|打印)(?:所有|完整)?(?:密钥|token|密码|secret|环境变量)",
                    Pattern.CASE_INSENSITIVE)));

    public PromptRiskResult scan(String message) {
        String candidate = message == null ? "" : message.trim();
        List<String> matched = new ArrayList<>();
        for (Signal signal : signals) {
            if (signal.pattern().matcher(candidate.toLowerCase(Locale.ROOT)).find()) {
                matched.add(signal.code());
            }
        }
        return new PromptRiskResult(!matched.isEmpty(), List.copyOf(matched));
    }

    public record PromptRiskResult(boolean blocked, List<String> signals) {
    }

    private record Signal(String code, Pattern pattern) {
    }
}
