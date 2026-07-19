package com.visionary.agent.config;

import com.visionary.agent.core.Agent;
import com.visionary.agent.core.MessageBus;
import com.visionary.agent.core.Tool;
import com.visionary.agent.tool.ArtifactPersistTool;
import com.visionary.agent.tool.RagRetrieveTool;
import com.visionary.mcp.McpToolBridge;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Auto-wires all Agent and Tool beans into maps that Supervisor can use.
 */
@Configuration
@RequiredArgsConstructor
public class AgentRegistryConfig {

    private final List<Agent> allAgents;
    private final List<Tool> allTools;
    private final MessageBus messageBus;
    private final RagRetrieveTool ragRetrieveTool;
    private final ArtifactPersistTool artifactPersistTool;
    private final com.visionary.agent.tool.ProfileMergeTool profileMergeTool;
    private final McpToolBridge mcpToolBridge;

    @Bean
    public Map<String, Agent> agentRegistry() {
        Map<String, Agent> registry = new HashMap<>();
        for (Agent agent : allAgents) {
            registry.put(agent.getRole(), agent);
        }
        return registry;
    }

    @Bean
    public Map<String, Tool> toolRegistry() {
        Map<String, Tool> registry = new HashMap<>();
        for (Tool tool : allTools) {
            registry.put(tool.getName(), tool);
        }
        // Ensure core tools are present even if not auto-discovered
        registry.putIfAbsent("RAGRetrieveTool", ragRetrieveTool);
        registry.putIfAbsent("ArtifactPersistTool", artifactPersistTool);
        registry.putIfAbsent("ProfileMergeTool", profileMergeTool);
        if (mcpToolBridge != null && mcpToolBridge.isActive()) {
            for (Tool mcpTool : mcpToolBridge.getCoreTools()) {
                registry.putIfAbsent(mcpTool.getName(), mcpTool);
            }
        }
        return registry;
    }
}