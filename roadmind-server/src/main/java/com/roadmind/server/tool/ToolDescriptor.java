package com.roadmind.server.tool;

import java.time.Duration;

public record ToolDescriptor(
        String name,
        String version,
        String description,
        ToolRiskLevel riskLevel,
        Duration timeout,
        RetryPolicy retryPolicy,
        boolean idempotent,
        String inputSchemaResource) {

    public ToolDescriptor {
        if (name == null || !name.matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")) {
            throw new IllegalArgumentException("工具名称不合法: " + name);
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("工具超时必须大于 0");
        }
    }
}
