package com.roadmind.server.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "roadmind.agent")
public class AgentProperties {

    private AgentMode mode = AgentMode.RULE_STUB;
    private String modelName = "gpt-4o-mini";
    private int maxToolCalls = 6;

    public AgentMode getMode() {
        return mode;
    }

    public void setMode(AgentMode mode) {
        this.mode = mode;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public int getMaxToolCalls() {
        return maxToolCalls;
    }

    public void setMaxToolCalls(int maxToolCalls) {
        this.maxToolCalls = maxToolCalls;
    }
}
