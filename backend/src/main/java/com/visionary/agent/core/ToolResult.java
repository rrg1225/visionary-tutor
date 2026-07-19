package com.visionary.agent.core;

import java.util.Map;

public record ToolResult(
        boolean success,
        String output,
        Map<String, Object> data
) {}