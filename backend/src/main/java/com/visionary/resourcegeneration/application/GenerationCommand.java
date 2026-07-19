package com.visionary.resourcegeneration.application;

import com.visionary.entity.GeneratedArtifact.ArtifactType;

import java.util.List;

public record GenerationCommand(
        Long learningSessionId,
        String topic,
        String learnerProfileSnapshot,
        String weakPointsSnapshot,
        String emotionSnapshot,
        List<ArtifactType> resourceTypes,
        String requestId
) {
    public GenerationCommand {
        resourceTypes = resourceTypes == null ? List.of() : List.copyOf(resourceTypes);
        requestId = requestId == null || requestId.isBlank() ? null : requestId.trim();
    }

    public GenerationCommand(
            Long learningSessionId,
            String topic,
            String learnerProfileSnapshot,
            String weakPointsSnapshot,
            String emotionSnapshot,
            List<ArtifactType> resourceTypes
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
