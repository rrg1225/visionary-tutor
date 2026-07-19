package com.visionary.agent.core;

import java.util.List;
import java.util.Map;

public record AgentResult(
        boolean success,
        String output,
        List<String> citations,
        Map<String, Object> metadata,   // new profile snapshot, path plan, video task id, etc.
        List<AgentHandoff> handoffs
) {}