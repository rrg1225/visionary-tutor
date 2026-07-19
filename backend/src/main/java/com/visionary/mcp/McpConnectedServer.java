package com.visionary.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;

/**
 * A live MCP server connection with discovered tools.
 */
public record McpConnectedServer(
        String name,
        McpSyncClient client,
        List<McpSchema.Tool> tools,
        boolean readOnly
) {
}
