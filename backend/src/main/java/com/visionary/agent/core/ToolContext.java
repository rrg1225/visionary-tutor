package com.visionary.agent.core;

import java.util.Map;

public record ToolContext(
        SharedBlackboard blackboard,
        String runId,
        Map<String, Object> metadata
) {}