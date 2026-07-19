package com.visionary.resourcegeneration.infrastructure;

import com.visionary.dto.ResourceGenerationResponse;
import com.visionary.resourcegeneration.application.GenerationCommand;
import com.visionary.resourcegeneration.application.GenerationExecutionPort;
import com.visionary.resourcegeneration.application.GenerationRequestAssembler;
import com.visionary.resourcegeneration.domain.OrchestrationMode;
import com.visionary.service.LegacyGenerationEngine;
import com.visionary.service.ResourceGenerationProgressListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MultiAgentGenerationExecutionAdapter implements GenerationExecutionPort {

    private final LegacyGenerationEngine legacyEngine;
    private final GenerationRequestAssembler requestAssembler;

    public MultiAgentGenerationExecutionAdapter(
            LegacyGenerationEngine legacyEngine,
            GenerationRequestAssembler requestAssembler
    ) {
        this.legacyEngine = legacyEngine;
        this.requestAssembler = requestAssembler;
    }

    @Override
    public ResourceGenerationResponse execute(
            GenerationCommand command,
            OrchestrationMode mode,
            ResourceGenerationProgressListener listener
    ) {
        ResourceGenerationResponse response = legacyEngine.generateWithStrategy(
                requestAssembler.toRequest(command), listener, mode);
        if (response == null || response.artifacts() == null || command.resourceTypes().isEmpty()) {
            return response;
        }
        List<com.visionary.entity.GeneratedArtifact.ArtifactType> requested = command.resourceTypes().stream()
                .map(type -> type == com.visionary.entity.GeneratedArtifact.ArtifactType.VIDEO_SCRIPT
                        ? com.visionary.entity.GeneratedArtifact.ArtifactType.VISUALIZATION
                        : type)
                .distinct()
                .toList();
        var scopedArtifacts = response.artifacts().stream()
                .filter(artifact -> artifact != null && requested.contains(artifact.getArtifactType()))
                .toList();
        return new ResourceGenerationResponse(
                response.runId(), scopedArtifacts, response.steps(), response.reviewSummary());
    }
}
