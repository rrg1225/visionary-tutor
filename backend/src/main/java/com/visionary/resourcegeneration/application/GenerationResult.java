package com.visionary.resourcegeneration.application;

import com.visionary.dto.ResourceGenerationResponse;
import com.visionary.resourcegeneration.domain.GenerationState;

public record GenerationResult(
        ResourceGenerationResponse response,
        GenerationState finalState,
        String orchestrationMode
) {
}
