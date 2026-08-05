package com.roadmind.server.adapter.simulator;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "roadmind.simulator")
public record SimulatorProperties(
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout) {
}
