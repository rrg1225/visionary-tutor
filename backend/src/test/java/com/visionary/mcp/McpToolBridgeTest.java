package com.visionary.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class McpToolBridgeTest {

    @Test
    void qualifiedToolName_usesPrefixAndServer() {
        assertEquals("mcp:filesystem:read_file",
                McpToolBridge.qualifiedToolName("mcp:", "filesystem", "read_file"));
    }

    @Test
    void parseQualifiedName_roundTrip() {
        McpToolBridge.ParsedMcpToolName parsed = McpToolBridge.parseQualifiedName(
                "mcp:yuque:search_docs", "mcp:");
        assertNotNull(parsed);
        assertEquals("yuque", parsed.serverName());
        assertEquals("search_docs", parsed.toolName());
    }

    @Test
    void parseQualifiedName_rejectsNonMcpNames() {
        assertNull(McpToolBridge.parseQualifiedName("generate_quiz", "mcp:"));
        assertNull(McpToolBridge.parseQualifiedName("mcp:invalid", "mcp:"));
    }

    @Test
    void bridgeInactiveWhenDisabled() {
        McpProperties props = new McpProperties();
        props.setEnabled(false);
        McpClientManager manager = new McpClientManager(props);
        McpToolBridge bridge = new McpToolBridge(manager, props, new com.fasterxml.jackson.databind.ObjectMapper());
        assertFalse(bridge.isActive());
        assertTrue(bridge.getSpecialistTools().isEmpty());
        assertTrue(bridge.getCoreTools().isEmpty());
    }
}
