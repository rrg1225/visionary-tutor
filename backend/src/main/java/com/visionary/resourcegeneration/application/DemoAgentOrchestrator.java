package com.visionary.resourcegeneration.application;

import com.visionary.dto.ResourceGenerationResponse;
import com.visionary.resourcegeneration.domain.GenerationState;
import com.visionary.resourcegeneration.domain.OrchestrationMode;
import com.visionary.service.ResourceGenerationProgressListener;
import org.springframework.stereotype.Component;

@Component
public class DemoAgentOrchestrator implements AgentOrchestrator {

    private final GenerationExecutionPort executionPort;

    public DemoAgentOrchestrator(GenerationExecutionPort executionPort) {
        this.executionPort = executionPort;
    }

    @Override
    public boolean supports(OrchestrationMode mode) {
        return mode == OrchestrationMode.DEMO;
    }

    @Override
    public GenerationResult execute(GenerationCommand command, ResourceGenerationProgressListener listener) {
        ResourceGenerationResponse response = executionPort.execute(command, OrchestrationMode.DEMO, listener);
        if (response == null || response.artifacts() == null || response.artifacts().isEmpty()) {
            return new GenerationResult(response, GenerationState.FAILED, "DEMO_MODE");
        }
        return new GenerationResult(response, GenerationState.SUCCEEDED, "DEMO_MODE");
    }
}
