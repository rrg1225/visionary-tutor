package com.visionary.mcp;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live MCP integration — requires Node.js + npx on PATH.
 */
class McpFilesystemIntegrationTest {

    @Test
    void connectFilesystemMcpServer_andListTools() throws Exception {
        assumeTrue(isNodeAvailable(), "Node/npx not available — skipping live MCP test");
        Path kb = Path.of("..", "ai_engine", "knowledge_base").toAbsolutePath().normalize();
        assumeTrue(Files.isDirectory(kb), "knowledge_base directory must exist: " + kb);

        McpProperties props = new McpProperties();
        props.setEnabled(true);
        props.setDefaultTimeoutMs(60_000);

        McpProperties.ServerConfig server = new McpProperties.ServerConfig();
        server.setName("filesystem");
        server.setEnabled(true);
        server.setTransport("stdio");
        server.setCommand(resolveNpxCommand());
        server.setArgs(java.util.List.of(
                "-y",
                "@modelcontextprotocol/server-filesystem",
                kb.toString()
        ));
        server.setAllowedTools(java.util.List.of("read_file", "list_directory"));
        server.setTimeoutMs(60_000);
        props.setServers(java.util.List.of(server));

        McpClientManager manager = new McpClientManager(props);
        try {
            manager.initializeIfNeeded();
            assumeTrue(!manager.getConnectedServers().isEmpty(),
                    "filesystem MCP server unavailable; skipping live MCP assertions");

            assertTrue(manager.totalToolCount() > 0, "should discover at least one tool");

            McpToolBridge bridge = new McpToolBridge(manager, props, new com.fasterxml.jackson.databind.ObjectMapper());
            assertTrue(bridge.isActive());
            assertFalse(bridge.getSpecialistTools().isEmpty());
            assertTrue(bridge.getSpecialistTools().stream()
                    .anyMatch(t -> t.getToolName().startsWith("mcp:filesystem:")));
        } finally {
            manager.shutdown();
        }
    }

    private static boolean isNodeAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder(resolveNpxCommand(), "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String resolveNpxCommand() {
        String onPath = System.getenv("PATH");
        if (onPath != null) {
            for (String dir : onPath.split(java.io.File.pathSeparator)) {
                Path candidate = Path.of(dir, "npx.cmd");
                if (Files.isRegularFile(candidate)) {
                    return candidate.toString();
                }
                candidate = Path.of(dir, "npx");
                if (Files.isRegularFile(candidate)) {
                    return candidate.toString();
                }
            }
        }
        return "npx";
    }
}
