package com.visionary.dto;

/**
 * SSE progress payload for resource generation ({@code agent_step} events).
 */
public record ResourceGenerationProgressEvent(
        String runId,
        String phase,
        String agentName,
        int stepOrder,
        String message,
        String detail,
        int progressPercent
) {
}
