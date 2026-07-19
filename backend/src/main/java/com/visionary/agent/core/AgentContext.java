package com.visionary.agent.core;

import java.util.Map;

/**
 * Context passed to every Agent.execute().
 * Contains shared blackboard, tool registry, memory access, and run metadata.
 */
public record AgentContext(
        SharedBlackboard blackboard,
        Map<String, Tool> tools,           // toolName -> Tool instance
        MessageBus messageBus,
        String runId,
        Map<String, Object> metadata
) {}