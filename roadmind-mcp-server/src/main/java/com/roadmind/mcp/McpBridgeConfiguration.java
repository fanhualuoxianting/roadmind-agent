package com.roadmind.mcp;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(McpBridgeProperties.class)
public class McpBridgeConfiguration {

    @Bean
    RestClient roadMindServerClient(RestClient.Builder builder, McpBridgeProperties properties) {
        return builder.baseUrl(properties.serverBaseUrl()).build();
    }
}
