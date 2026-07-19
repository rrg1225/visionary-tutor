package com.visionary.agent.core;

import java.util.Collections;
import java.util.Set;

/**
 * Core Agent interface for the new multi-agent architecture.
 * Every specialist (Planner, Critic, Doc, Quiz, etc.) and Supervisor implements this.
 */
public interface Agent {

    String getRole();

    default Set<String> getSupportedTools() {
        return Collections.emptySet();
    }

    AgentResult execute(AgentTask task, AgentContext context);

    default boolean canHandle(AgentTask task) {
        return task.requiredRoles() != null && task.requiredRoles().contains(getRole());
    }
}
