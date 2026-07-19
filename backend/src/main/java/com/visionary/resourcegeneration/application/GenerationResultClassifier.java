package com.visionary.resourcegeneration.application;

import com.visionary.dto.ResourceGenerationResponse;
import com.visionary.entity.GeneratedArtifact;
import com.visionary.resourcegeneration.domain.GenerationState;

final class GenerationResultClassifier {

    private GenerationResultClassifier() {
    }

    static GenerationState classify(ResourceGenerationResponse response) {
        if (response == null || response.artifacts() == null || response.artifacts().isEmpty()) {
            return GenerationState.FAILED;
        }
        long publishableCount = response.artifacts().stream()
                .filter(artifact -> artifact != null && !"BLOCKED".equalsIgnoreCase(artifact.getPublishStatus()))
                .count();
        if (publishableCount == 0) {
            // The workflow did produce artifacts, but the publish gate held all
            // of them for review. This is materially different from a zero-output
            // failure and must not be reported to the learner as "no artifacts".
            return GenerationState.MANUAL_REVIEW;
        }
        boolean degraded = response.artifacts().stream().anyMatch(GenerationResultClassifier::isDegraded);
        return degraded ? GenerationState.DEGRADED : GenerationState.SUCCEEDED;
    }

    private static boolean isDegraded(GeneratedArtifact artifact) {
        if (artifact == null) {
            return true;
        }
        String publishStatus = artifact.getPublishStatus();
        String validationStatus = artifact.getValidationStatus();
        return "DEGRADED".equalsIgnoreCase(publishStatus)
                || "BLOCKED".equalsIgnoreCase(publishStatus)
                || validationStatus == null
                || validationStatus.isBlank()
                || "UNVERIFIED".equalsIgnoreCase(validationStatus)
                || "WEAK_GROUNDING".equalsIgnoreCase(validationStatus)
                || (artifact.getContentJson() != null && artifact.getContentJson().contains("\"degraded\":true"));
    }
}
