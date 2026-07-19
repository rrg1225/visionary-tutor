package com.visionary.resourcegeneration.application;

import com.visionary.dto.ResourceGenerationRequest;
import com.visionary.dto.ResourceGenerationResponse;
import com.visionary.resourcegeneration.domain.OrchestrationMode;
import com.visionary.service.ResourceGenerationProgressListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.visionary.platform.observability.GenerationObservability;

@Service
public class ResourceGenerationCoordinator implements ResourceGenerationUseCase {

    private final GenerationRequestAssembler requestAssembler;
    private final AgentOrchestratorSelector orchestratorSelector;
    private final GenerationIdempotencyService idempotencyService;
    private final GenerationObservability observability;
    private final boolean demoEnabled;
    private final OrchestrationMode configuredMode;

    public ResourceGenerationCoordinator(
            GenerationRequestAssembler requestAssembler,
            AgentOrchestratorSelector orchestratorSelector,
            GenerationIdempotencyService idempotencyService,
            GenerationObservability observability,
            @Value("${visionary.demo-mode.enabled:false}") boolean demoEnabled,
            @Value("${agent.mode:react}") String configuredMode
    ) {
        this.requestAssembler = requestAssembler;
        this.orchestratorSelector = orchestratorSelector;
        this.idempotencyService = idempotencyService;
        this.observability = observability;
        this.demoEnabled = demoEnabled;
        this.configuredMode = OrchestrationMode.fromConfiguration(configuredMode);
    }

    @Override
    public ResourceGenerationResponse generate(ResourceGenerationRequest request) {
        return generate(request, null);
    }

    @Override
    public ResourceGenerationResponse generate(
            ResourceGenerationRequest request,
            ResourceGenerationProgressListener listener
    ) {
        GenerationCommand command = requestAssembler.toCommand(request);
        OrchestrationMode mode = demoEnabled ? OrchestrationMode.DEMO : configuredMode;
        if (mode == OrchestrationMode.DEMO && !demoEnabled) {
            throw new IllegalStateException("Demo orchestration requires visionary.demo-mode.enabled=true");
        }
        return idempotencyService.execute(command, () -> observability.observe(mode.name(), () -> {
            GenerationResult result = orchestratorSelector.select(mode).execute(command, listener);
            if (result.finalState() == com.visionary.resourcegeneration.domain.GenerationState.FAILED) {
                throw new IllegalStateException("Resource generation produced no artifacts in mode " + mode);
            }
            return result.response();
        }));
    }
}
