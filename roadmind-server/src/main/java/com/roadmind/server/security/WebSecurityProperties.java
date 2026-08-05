package com.roadmind.server.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "roadmind.web")
public record WebSecurityProperties(List<String> allowedOrigins) {
}
