package com.visionary.agent.core;

import java.util.Map;

public record AgentHandoff(
        String toRole,
        Map<String, Object> payload
) {}