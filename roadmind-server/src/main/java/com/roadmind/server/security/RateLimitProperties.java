package com.roadmind.server.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "roadmind.rate-limit")
public class RateLimitProperties {

    private boolean enabled = true;
    private int windowSeconds = 60;
    private int userMessageLimit = 30;
    private int toolLimit = 60;
    private int confirmationLimit = 30;
    private int sseLimit = 8;

    public boolean enabled() {
        return enabled;
    }

    public int windowSeconds() {
        return windowSeconds;
    }

    public int userMessageLimit() {
        return userMessageLimit;
    }

    public int toolLimit() {
        return toolLimit;
    }

    public int confirmationLimit() {
        return confirmationLimit;
    }

    public int sseLimit() {
        return sseLimit;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setWindowSeconds(int windowSeconds) {
        this.windowSeconds = windowSeconds;
    }

    public void setUserMessageLimit(int userMessageLimit) {
        this.userMessageLimit = userMessageLimit;
    }

    public void setToolLimit(int toolLimit) {
        this.toolLimit = toolLimit;
    }

    public void setConfirmationLimit(int confirmationLimit) {
        this.confirmationLimit = confirmationLimit;
    }

    public void setSseLimit(int sseLimit) {
        this.sseLimit = sseLimit;
    }
}
