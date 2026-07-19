package com.visionary.dto;

import com.visionary.entity.GeneratedArtifact;

import java.util.List;

public record TutoringMultimodalResponse(
        String runId,
        List<GeneratedArtifact> artifacts,
        String message
) {
}
