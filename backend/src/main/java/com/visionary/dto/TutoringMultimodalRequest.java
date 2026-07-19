package com.visionary.dto;

import com.visionary.entity.GeneratedArtifact;

import java.util.List;

public record TutoringMultimodalRequest(
        Long learningSessionId,
        String question,
        String topic,
        String dialogueContext,
        String learnerProfileSnapshot,
        List<GeneratedArtifact.ArtifactType> modes
) {
}
