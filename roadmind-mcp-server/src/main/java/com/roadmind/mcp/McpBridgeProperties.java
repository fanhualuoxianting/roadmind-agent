package com.roadmind.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "roadmind.bridge")
public record McpBridgeProperties(String serverBaseUrl, String internalToken) {
}
