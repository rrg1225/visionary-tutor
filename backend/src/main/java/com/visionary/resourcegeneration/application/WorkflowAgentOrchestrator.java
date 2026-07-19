package com.visionary.resourcegeneration.application;

import com.visionary.dto.ResourceGenerationResponse;
import com.visionary.resourcegeneration.domain.OrchestrationMode;
import com.visionary.service.ResourceGenerationProgressListener;
import org.springframework.stereotype.Component;

@Component
public class WorkflowAgentOrchestrator implements AgentOrchestrator {

    private final GenerationExecutionPort executionPort;

    public WorkflowAgentOrchestrator(GenerationExecutionPort executionPort) {
        this.executionPort = executionPort;
    }

    @Override
    public boolean supports(OrchestrationMode mode) {
        return mode == OrchestrationMode.WORKFLOW;
    }

    @Override
    public GenerationResult execute(GenerationCommand command, ResourceGenerationProgressListener listener) {
        ResourceGenerationResponse response = executionPort.execute(command, OrchestrationMode.WORKFLOW, listener);
        return new GenerationResult(response, GenerationResultClassifier.classify(response), "DETERMINISTIC_WORKFLOW");
    }
}
