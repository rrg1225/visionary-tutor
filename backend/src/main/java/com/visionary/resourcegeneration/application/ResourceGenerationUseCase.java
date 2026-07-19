package com.visionary.resourcegeneration.application;

import com.visionary.dto.ResourceGenerationRequest;
import com.visionary.dto.ResourceGenerationResponse;
import com.visionary.service.ResourceGenerationProgressListener;

public interface ResourceGenerationUseCase {

    ResourceGenerationResponse generate(ResourceGenerationRequest request);

    ResourceGenerationResponse generate(
            ResourceGenerationRequest request,
            ResourceGenerationProgressListener listener
    );
}
