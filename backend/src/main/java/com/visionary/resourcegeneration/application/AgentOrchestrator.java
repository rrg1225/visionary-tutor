package com.visionary.resourcegeneration.application;

public interface AgentOrchestrator {

    boolean supports(com.visionary.resourcegeneration.domain.OrchestrationMode mode);

    GenerationResult execute(
            GenerationCommand command,
            com.visionary.service.ResourceGenerationProgressListener listener
    );
}
