package com.visionary.resourcegeneration.application;

import com.visionary.resourcegeneration.domain.OrchestrationMode;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AgentOrchestratorSelector {

    private final List<AgentOrchestrator> orchestrators;

    public AgentOrchestratorSelector(List<AgentOrchestrator> orchestrators) {
        this.orchestrators = List.copyOf(orchestrators);
    }

    public AgentOrchestrator select(OrchestrationMode mode) {
        return orchestrators.stream()
                .filter(orchestrator -> orchestrator.supports(mode))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No AgentOrchestrator registered for mode " + mode));
    }
}
