package com.visionary.agent.core;

import java.util.List;
import java.util.Map;

public record AgentTask(
        String taskId,
        String type,                    // RESOURCE_GENERATION, TUTORING, PROFILE_UPDATE, PATH_REPLAN...
        Map<String, Object> input,
        List<String> requiredRoles
) {}