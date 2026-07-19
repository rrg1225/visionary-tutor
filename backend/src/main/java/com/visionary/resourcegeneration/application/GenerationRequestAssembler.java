package com.visionary.resourcegeneration.application;

import com.visionary.dto.ResourceGenerationRequest;
import org.springframework.stereotype.Component;

import static com.visionary.entity.GeneratedArtifact.ArtifactType.VIDEO_SCRIPT;

@Component
public class GenerationRequestAssembler {

    public GenerationCommand toCommand(ResourceGenerationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Resource generation request is required");
        }
        if (request.learningSessionId() == null) {
            throw new IllegalArgumentException("Learning session id is required");
        }
        if (request.resourceTypes() != null && request.resourceTypes().contains(VIDEO_SCRIPT)) {
            throw new IllegalArgumentException(
                    "VIDEO_SCRIPT generation has been retired; request VISUALIZATION for a local animation instead"
            );
        }
        return new GenerationCommand(
                request.learningSessionId(),
                request.topic(),
                request.learnerProfileSnapshot(),
                request.weakPointsSnapshot(),
                request.emotionSnapshot(),
                request.resourceTypes(),
                request.requestId()
        );
    }

    public ResourceGenerationRequest toRequest(GenerationCommand command) {
        return new ResourceGenerationRequest(
                command.learningSessionId(),
                command.topic(),
                command.learnerProfileSnapshot(),
                command.weakPointsSnapshot(),
                command.emotionSnapshot(),
                command.resourceTypes().isEmpty() ? null : command.resourceTypes(),
                command.requestId()
        );
    }
}
