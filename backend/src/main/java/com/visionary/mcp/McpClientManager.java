package com.visionary.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
public class McpClientManager {

    private final McpProperties properties;
    private final McpJsonMapper jsonMapper;
    private final List<McpConnectedServer> connectedServers = new ArrayList<>();
    private volatile boolean initialized;

    public McpClientManager(McpProperties properties) {
        this.properties = properties;
        this.jsonMapper = McpJsonDefaults.getMapper();
    }

    public synchronized void initializeIfNeeded() {
        if (initialized || !properties.isEnabled()) {
            return;
        }
        for (McpProperties.ServerConfig serverConfig : properties.getServers()) {
            if (serverConfig == null || !serverConfig.isEnabled()) {
                continue;
            }
            connectServer(serverConfig).ifPresent(connectedServers::add);
        }
        initialized = true;
        log.info("[MCP] Initialized {} server(s), {} tool(s) discovered",
                connectedServers.size(), totalToolCount());
    }

    public List<McpConnectedServer> getConnectedServers() {
        initializeIfNeeded();
        return Collections.unmodifiableList(connectedServers);
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public int totalToolCount() {
        return connectedServers.stream().mapToInt(s -> s.tools().size()).sum();
    }

    Optional<McpConnectedServer> findServer(String serverName) {
        return getConnectedServers().stream()
                .filter(s -> s.name().equals(serverName))
                .findFirst();
    }

    public McpSchema.CallToolResult callTool(String serverName, String toolName, Map<String, Object> arguments) {
        McpConnectedServer server = findServer(serverName)
                .orElseThrow(() -> new IllegalStateException("MCP server not connected: " + serverName));
        McpSyncClient client = server.client();
        try {
            Map<String, Object> args = arguments != null ? arguments : Map.of();
            return client.callTool(new McpSchema.CallToolRequest(toolName, args));
        } catch (Exception e) {
            throw new McpToolExecutionException(
                    "MCP tools/call failed: server=" + serverName + ", tool=" + toolName + ": " + e.getMessage(), e);
        }
    }

    private Optional<McpConnectedServer> connectServer(McpProperties.ServerConfig config) {
        String name = config.getName();
        if (name == null || name.isBlank()) {
            log.warn("[MCP] Skip server with blank name");
            return Optional.empty();
        }
        try {
            McpClientTransport transport = buildTransport(config);
            Duration timeout = Duration.ofMillis(resolveTimeout(config));
            McpSyncClient client = McpClient.sync(transport)
                    .requestTimeout(timeout)
                    .build();
            client.initialize();
            McpSchema.ListToolsResult toolsResult = client.listTools();
            List<McpSchema.Tool> tools = toolsResult.tools() != null ? toolsResult.tools() : List.of();
            log.info("[MCP] Connected server '{}' via {} — {} tool(s): {}",
                    name, config.getTransport(), tools.size(),
                    tools.stream().map(McpSchema.Tool::name).toList());
            return Optional.of(new McpConnectedServer(name, client, tools, config.isReadOnly()));
        } catch (Exception e) {
            log.warn("[MCP] Failed to connect server '{}': {}", name, e.getMessage());
            return Optional.empty();
        }
    }

    private McpClientTransport buildTransport(McpProperties.ServerConfig config) {
        String transport = config.getTransport() != null
                ? config.getTransport().toLowerCase(Locale.ROOT)
                : "stdio";
        return switch (transport) {
            case "stdio" -> {
                if (config.getCommand() == null || config.getCommand().isBlank()) {
                    throw new IllegalArgumentException("stdio transport requires command for server: " + config.getName());
                }
                ServerParameters.Builder builder = ServerParameters.builder(config.getCommand());
                if (config.getArgs() != null) {
                    builder.args(config.getArgs());
                }
                yield new StdioClientTransport(builder.build(), jsonMapper);
            }
            case "sse" -> {
                if (config.getUrl() == null || config.getUrl().isBlank()) {
                    throw new IllegalArgumentException("sse transport requires url for server: " + config.getName());
                }
                HttpClientSseClientTransport.Builder sseBuilder = HttpClientSseClientTransport.builder(config.getUrl())
                        .jsonMapper(jsonMapper);
                if (config.getEndpoint() != null && !config.getEndpoint().isBlank()) {
                    sseBuilder.sseEndpoint(config.getEndpoint());
                }
                yield sseBuilder.build();
            }
            case "streamable-http", "streamable_http", "http" -> {
                if (config.getUrl() == null || config.getUrl().isBlank()) {
                    throw new IllegalArgumentException("streamable-http transport requires url for server: " + config.getName());
                }
                HttpClientStreamableHttpTransport.Builder httpBuilder = HttpClientStreamableHttpTransport.builder(config.getUrl())
                        .jsonMapper(jsonMapper);
                if (config.getEndpoint() != null && !config.getEndpoint().isBlank()) {
                    httpBuilder.endpoint(config.getEndpoint());
                }
                yield httpBuilder.build();
            }
            default -> throw new IllegalArgumentException("Unsupported MCP transport: " + transport);
        };
    }

    private long resolveTimeout(McpProperties.ServerConfig config) {
        return config.getTimeoutMs() > 0 ? config.getTimeoutMs() : properties.getDefaultTimeoutMs();
    }

    @PreDestroy
    public void shutdown() {
        for (McpConnectedServer server : connectedServers) {
            try {
                server.client().closeGracefully();
                log.info("[MCP] Closed server '{}'", server.name());
            } catch (Exception e) {
                log.warn("[MCP] Error closing server '{}': {}", server.name(), e.getMessage());
            }
        }
        connectedServers.clear();
        initialized = false;
    }
}
