package com.visionary.mcp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(McpProperties.class)
@ConditionalOnProperty(prefix = "agent.mcp", name = "enabled", havingValue = "true")
public class McpConfiguration {
}
