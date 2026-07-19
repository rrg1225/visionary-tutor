package com.visionary.resourcegeneration.application;

import com.visionary.dto.ResourceGenerationResponse;
import com.visionary.resourcegeneration.domain.OrchestrationMode;
import com.visionary.service.ResourceGenerationProgressListener;
import org.springframework.stereotype.Component;

@Component
public class ReActAgentOrchestrator implements AgentOrchestrator {

    private final GenerationExecutionPort executionPort;

    public ReActAgentOrchestrator(GenerationExecutionPort executionPort) {
        this.executionPort = executionPort;
    }

    @Override
    public boolean supports(OrchestrationMode mode) {
        return mode == OrchestrationMode.REACT;
    }

    @Override
    public GenerationResult execute(GenerationCommand command, ResourceGenerationProgressListener listener) {
        ResourceGenerationResponse response = executionPort.execute(command, OrchestrationMode.REACT, listener);
        return new GenerationResult(response, GenerationResultClassifier.classify(response), "REACT_MULTI_AGENT");
    }
}
