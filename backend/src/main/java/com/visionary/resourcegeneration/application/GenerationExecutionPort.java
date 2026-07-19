package com.visionary.resourcegeneration.application;

import com.visionary.dto.ResourceGenerationResponse;
import com.visionary.resourcegeneration.domain.OrchestrationMode;
import com.visionary.service.ResourceGenerationProgressListener;

public interface GenerationExecutionPort {

    ResourceGenerationResponse execute(
            GenerationCommand command,
            OrchestrationMode mode,
            ResourceGenerationProgressListener listener
    );
}
