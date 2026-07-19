package com.visionary.dto;

import com.visionary.entity.AgentRunStep;
import com.visionary.entity.GeneratedArtifact;

import java.util.List;

public record ResourceGenerationResponse(
        String runId,
        List<GeneratedArtifact> artifacts,
        List<AgentRunStep> steps,
        String reviewSummary
) {
}
