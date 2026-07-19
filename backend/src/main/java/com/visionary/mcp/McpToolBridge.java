package com.visionary.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.visionary.agent.core.Tool;
import com.visionary.agent.core.ToolContext;
import com.visionary.agent.core.ToolResult;
import com.visionary.agent.tools.SpecialistTool;
import com.visionary.agent.tools.SpecialistTool.ReActContext;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
public class McpToolBridge {

    @Getter
    private final List<SpecialistTool> specialistTools = new ArrayList<>();

    @Getter
    private final List<Tool> coreTools = new ArrayList<>();

    private final McpClientManager clientManager;
    private final McpProperties properties;
    private final ObjectMapper objectMapper;

    public McpToolBridge(McpClientManager clientManager, McpProperties properties, ObjectMapper objectMapper) {
        this.clientManager = clientManager;
        this.properties = properties;
        this.objectMapper = objectMapper;
        refresh();
    }

    public boolean isActive() {
        return properties.isEnabled() && !specialistTools.isEmpty();
    }

    public synchronized void refresh() {
        specialistTools.clear();
        coreTools.clear();
        if (!properties.isEnabled()) {
            log.info("[MCP] Bridge inactive (agent.mcp.enabled=false)");
            return;
        }
        clientManager.initializeIfNeeded();
        for (McpConnectedServer server : clientManager.getConnectedServers()) {
            for (McpSchema.Tool tool : server.tools()) {
                if (!isAllowed(server.name(), tool.name())) {
                    continue;
                }
                String qualifiedName = qualifiedToolName(server.name(), tool.name());
                McpSpecialistToolAdapter specialistAdapter = new McpSpecialistToolAdapter(
                        qualifiedName, server.name(), tool, clientManager, objectMapper, server.readOnly());
                specialistTools.add(specialistAdapter);
                coreTools.add(new McpCoreToolAdapter(specialistAdapter));
                log.debug("[MCP] Registered tool {}", qualifiedName);
            }
        }
        log.info("[MCP] Bridge registered {} MCP tool(s)", specialistTools.size());
    }

    public static String qualifiedToolName(String prefix, String serverName, String toolName) {
        String p = prefix != null ? prefix : "mcp:";
        return p + serverName + ":" + toolName;
    }

    private String qualifiedToolName(String serverName, String toolName) {
        return qualifiedToolName(properties.getToolNamePrefix(), serverName, toolName);
    }

    private boolean isAllowed(String serverName, String toolName) {
        McpProperties.ServerConfig config = properties.getServers().stream()
                .filter(s -> serverName.equals(s.getName()))
                .findFirst()
                .orElse(null);
        if (config == null) {
            return false;
        }
        List<String> allowed = config.getAllowedTools();
        if (allowed == null || allowed.isEmpty()) {
            return true;
        }
        return allowed.stream().anyMatch(a -> a.equalsIgnoreCase(toolName));
    }

    /**
     * Parse {@code mcp:server:tool} into server + tool components.
     */
    public static ParsedMcpToolName parseQualifiedName(String qualifiedName, String prefix) {
        String p = prefix != null ? prefix : "mcp:";
        if (qualifiedName == null || !qualifiedName.startsWith(p)) {
            return null;
        }
        String remainder = qualifiedName.substring(p.length());
        int idx = remainder.indexOf(':');
        if (idx <= 0 || idx >= remainder.length() - 1) {
            return null;
        }
        return new ParsedMcpToolName(remainder.substring(0, idx), remainder.substring(idx + 1));
    }

    public record ParsedMcpToolName(String serverName, String toolName) {
    }

    static final class McpSpecialistToolAdapter implements SpecialistTool {

        private final String qualifiedName;
        private final String serverName;
        private final McpSchema.Tool tool;
        private final McpClientManager clientManager;
        private final ObjectMapper objectMapper;
        private final boolean readOnly;

        McpSpecialistToolAdapter(
                String qualifiedName,
                String serverName,
                McpSchema.Tool tool,
                McpClientManager clientManager,
                ObjectMapper objectMapper,
                boolean readOnly
        ) {
            this.qualifiedName = qualifiedName;
            this.serverName = serverName;
            this.tool = tool;
            this.clientManager = clientManager;
            this.objectMapper = objectMapper;
            this.readOnly = readOnly;
        }

        @Override
        public String getToolName() {
            return qualifiedName;
        }

        String getServerName() {
            return serverName;
        }

        String getNativeToolName() {
            return tool.name();
        }

        McpSchema.Tool getMcpToolDefinition() {
            return tool;
        }

        ObjectMapper getObjectMapper() {
            return objectMapper;
        }

        @Override
        public String executeTool(ObjectNode args, ReActContext ctx) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> arguments = objectMapper.convertValue(args, Map.class);
                McpSchema.CallToolResult result = clientManager.callTool(serverName, tool.name(), arguments);
                return formatCallResult(result);
            } catch (Exception e) {
                return formatError(e.getMessage());
            }
        }

        private String formatCallResult(McpSchema.CallToolResult result) {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("success", !Boolean.TRUE.equals(result.isError()));
            root.put("mcpServer", serverName);
            root.put("mcpTool", tool.name());
            root.put("readOnly", readOnly);
            if (result.content() != null) {
                root.set("content", objectMapper.valueToTree(result.content()));
            }
            try {
                return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
            } catch (Exception e) {
                return root.toString();
            }
        }

        private String formatError(String message) {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("success", false);
            root.put("mcpServer", serverName);
            root.put("mcpTool", tool.name());
            root.put("error", message);
            try {
                return objectMapper.writeValueAsString(root);
            } catch (Exception e) {
                return "{\"success\":false,\"error\":\"" + message + "\"}";
            }
        }
    }

    static final class McpCoreToolAdapter implements Tool {

        private final McpSpecialistToolAdapter delegate;

        McpCoreToolAdapter(McpSpecialistToolAdapter delegate) {
            this.delegate = delegate;
        }

        @Override
        public String getName() {
            return delegate.getToolName();
        }

        @Override
        public String getDescription() {
            McpSchema.Tool mcpTool = delegate.getMcpToolDefinition();
            return "[MCP:" + delegate.getServerName() + "] "
                    + (mcpTool.description() != null ? mcpTool.description() : delegate.getNativeToolName());
        }

        @Override
        public JsonNode getParametersSchema() {
            McpSchema.Tool mcpTool = delegate.getMcpToolDefinition();
            if (mcpTool.inputSchema() != null) {
                return delegate.getObjectMapper().valueToTree(mcpTool.inputSchema());
            }
            return delegate.getObjectMapper().createObjectNode().put("type", "object");
        }

        @Override
        public ToolResult execute(JsonNode arguments, ToolContext context) {
            ObjectNode args = arguments != null && arguments.isObject()
                    ? (ObjectNode) arguments
                    : delegate.objectMapper.createObjectNode();
            ReActContext reactCtx = new ReActContext(
                    context != null ? context.runId() : null,
                    null,
                    context != null && context.blackboard() != null ? context.blackboard().getCurrentTopic() : null,
                    null,
                    null,
                    null
            );
            String output = delegate.executeTool(args, reactCtx);
            boolean success = !output.contains("\"success\": false") && !output.contains("\"success\":false");
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("mcpServer", delegate.getServerName());
            data.put("mcpTool", delegate.getNativeToolName());
            return new ToolResult(success, output, data);
        }
    }
}
