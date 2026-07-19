package com.visionary.dto;

import com.visionary.entity.GeneratedArtifact;

import java.util.List;

public record ResourceGenerationRequest(
        Long learningSessionId,
        String topic,
        String learnerProfileSnapshot,
        String weakPointsSnapshot,
        String emotionSnapshot,
        List<GeneratedArtifact.ArtifactType> resourceTypes,
        String requestId
) {

    public ResourceGenerationRequest(
            Long learningSessionId,
            String topic,
            String learnerProfileSnapshot,
            String weakPointsSnapshot,
            String emotionSnapshot,
            List<GeneratedArtifact.ArtifactType> resourceTypes
    ) {
        this(
                learningSessionId,
                topic,
                learnerProfileSnapshot,
                weakPointsSnapshot,
                emotionSnapshot,
                resourceTypes,
                null
        );
    }
}
