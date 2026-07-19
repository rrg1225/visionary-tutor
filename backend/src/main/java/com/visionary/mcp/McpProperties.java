package com.visionary.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "agent.mcp")
public class McpProperties {

    /**
     * Master switch. When false, no MCP clients are created.
     */
    private boolean enabled = false;

    private String toolNamePrefix = "mcp:";

    private long defaultTimeoutMs = 15_000;

    private int maxRetries = 1;

    private List<ServerConfig> servers = new ArrayList<>();

    @Data
    public static class ServerConfig {
        private String name;
        private boolean enabled = true;
        /**
         * stdio | sse | streamable-http
         */
        private String transport = "stdio";
        private String command;
        private List<String> args = new ArrayList<>();
        private String url;
        private String endpoint = "/mcp";
        private Map<String, String> headers = new HashMap<>();
        private List<String> allowedTools = new ArrayList<>();
        private boolean readOnly = true;
        private long timeoutMs = 0;
    }
}
