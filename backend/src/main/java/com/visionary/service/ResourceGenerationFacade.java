package com.visionary.service;

import com.visionary.dto.ResourceCard;
import com.visionary.dto.ResourceGenerationRequest;
import com.visionary.dto.ResourceGenerationResponse;
import com.visionary.entity.GeneratedArtifact;
import com.visionary.resourcegeneration.application.ResourceGenerationUseCase;
import com.visionary.resourcegeneration.application.ArtifactQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ResourceGenerationFacade {

    private final ArtifactQueryService artifactQueryService;
    private final ResourceGenerationUseCase resourceGenerationUseCase;

    @Transactional(readOnly = true)
    public List<GeneratedArtifact> listArtifacts(Long learningSessionId) {
        return artifactQueryService.listArtifacts(learningSessionId);
    }

    @Transactional(readOnly = true)
    public List<ResourceCard> listResourceCards(Long learningSessionId) {
        return artifactQueryService.listResourceCards(learningSessionId);
    }

    @Transactional
    public ResourceGenerationResponse generate(ResourceGenerationRequest request) {
        return resourceGenerationUseCase.generate(request);
    }

    @Transactional
    public ResourceGenerationResponse generate(
            ResourceGenerationRequest request,
            ResourceGenerationProgressListener listener
    ) {
        return resourceGenerationUseCase.generate(request, listener);
    }

}
