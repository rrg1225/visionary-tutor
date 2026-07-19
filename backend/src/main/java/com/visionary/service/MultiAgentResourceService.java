package com.visionary.service;

import com.visionary.dto.ResourceCard;
import com.visionary.dto.ResourceGenerationRequest;
import com.visionary.dto.ResourceGenerationResponse;
import com.visionary.dto.TutoringMultimodalRequest;
import com.visionary.dto.TutoringMultimodalResponse;
import com.visionary.entity.GeneratedArtifact;
import com.visionary.resourcegeneration.domain.OrchestrationMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Compatibility adapter for historical callers.
 *
 * New generation entry points must depend on ResourceGenerationUseCase. The
 * infrastructure execution adapter is the only production caller allowed to
 * select an orchestration mode explicitly.
 */
@Service
@RequiredArgsConstructor
@Deprecated(forRemoval = false)
public class MultiAgentResourceService {

    private final LegacyGenerationEngine legacyEngine;

    public List<GeneratedArtifact> listArtifacts(Long learningSessionId) {
        return legacyEngine.listArtifacts(learningSessionId);
    }

    public List<ResourceCard> listResourceCards(Long learningSessionId) {
        return legacyEngine.listResourceCards(learningSessionId);
    }

    /** @deprecated use ResourceGenerationUseCase through ResourceGenerationCoordinator. */
    @Deprecated(forRemoval = false)
    public ResourceGenerationResponse generate(ResourceGenerationRequest request) {
        return legacyEngine.generate(request);
    }

    /** @deprecated use ResourceGenerationUseCase through ResourceGenerationCoordinator. */
    @Deprecated(forRemoval = false)
    public ResourceGenerationResponse generate(
            ResourceGenerationRequest request,
            ResourceGenerationProgressListener listener
    ) {
        return legacyEngine.generate(request, listener);
    }

    public ResourceGenerationResponse generateWithStrategy(
            ResourceGenerationRequest request,
            ResourceGenerationProgressListener listener,
            OrchestrationMode orchestrationMode
    ) {
        return legacyEngine.generateWithStrategy(request, listener, orchestrationMode);
    }

    public TutoringMultimodalResponse generateTutoringMultimodal(TutoringMultimodalRequest request) {
        return legacyEngine.generateTutoringMultimodal(request);
    }

}
