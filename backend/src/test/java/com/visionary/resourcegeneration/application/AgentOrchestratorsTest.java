package com.visionary.resourcegeneration.application;

import com.visionary.dto.ResourceGenerationRequest;
import com.visionary.dto.ResourceGenerationResponse;
import com.visionary.entity.GeneratedArtifact;
import com.visionary.entity.GeneratedArtifact.ArtifactType;
import com.visionary.resourcegeneration.infrastructure.MultiAgentGenerationExecutionAdapter;
import com.visionary.resourcegeneration.domain.GenerationState;
import com.visionary.resourcegeneration.domain.OrchestrationMode;
import com.visionary.platform.observability.GenerationObservability;
import com.visionary.service.LegacyGenerationEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.Clock;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentOrchestratorsTest {

    private final GenerationCommand command = new GenerationCommand(1L, "CNN", "{}", "padding", null, List.of());

    @Test
    void eachStrategyOwnsExactlyOneExecutionMode() {
        GenerationExecutionPort port = mock(GenerationExecutionPort.class);
        ResourceGenerationResponse live = response("PUBLISHED", "{}");
        when(port.execute(eq(command), any(), any())).thenReturn(live);

        ReActAgentOrchestrator react = new ReActAgentOrchestrator(port);
        WorkflowAgentOrchestrator workflow = new WorkflowAgentOrchestrator(port);
        DemoAgentOrchestrator demo = new DemoAgentOrchestrator(port);

        assertTrue(react.supports(OrchestrationMode.REACT));
        assertFalse(react.supports(OrchestrationMode.WORKFLOW));
        assertTrue(workflow.supports(OrchestrationMode.WORKFLOW));
        assertTrue(demo.supports(OrchestrationMode.DEMO));
        assertEquals("REACT_MULTI_AGENT", react.execute(command, null).orchestrationMode());
        assertEquals("DETERMINISTIC_WORKFLOW", workflow.execute(command, null).orchestrationMode());
        assertEquals("DEMO_MODE", demo.execute(command, null).orchestrationMode());
        verify(port).execute(command, OrchestrationMode.REACT, null);
        verify(port).execute(command, OrchestrationMode.WORKFLOW, null);
        verify(port).execute(command, OrchestrationMode.DEMO, null);
    }

    @Test
    void resultClassifierDisclosesDegradedAndFailedRuns() {
        GenerationExecutionPort port = mock(GenerationExecutionPort.class);
        ReActAgentOrchestrator react = new ReActAgentOrchestrator(port);
        when(port.execute(command, OrchestrationMode.REACT, null))
                .thenReturn(response("DEGRADED", "{\"degraded\":true}"));
        assertEquals(GenerationState.DEGRADED, react.execute(command, null).finalState());

        DemoAgentOrchestrator demo = new DemoAgentOrchestrator(port);
        when(port.execute(command, OrchestrationMode.DEMO, null))
                .thenReturn(new ResourceGenerationResponse("empty", List.of(), List.of(), "empty"));
        assertEquals(GenerationState.FAILED, demo.execute(command, null).finalState());
    }

    @Test
    void executionAdapterReturnsOnlyExplicitlyRequestedResourceTypes() {
        LegacyGenerationEngine engine = mock(LegacyGenerationEngine.class);
        GenerationRequestAssembler assembler = new GenerationRequestAssembler();
        GenerationCommand scopedCommand = new GenerationCommand(
                1L, "CNN", "{}", "padding", null, List.of(ArtifactType.VISUALIZATION));
        GeneratedArtifact animation = artifact(ArtifactType.VISUALIZATION);
        GeneratedArtifact code = artifact(ArtifactType.CODE_PRACTICE);
        when(engine.generateWithStrategy(any(), eq(null), eq(OrchestrationMode.REACT)))
                .thenReturn(new ResourceGenerationResponse(
                        "run-scoped", List.of(animation, code), List.of(), "ok"));

        ResourceGenerationResponse response = new MultiAgentGenerationExecutionAdapter(engine, assembler)
                .execute(scopedCommand, OrchestrationMode.REACT, null);

        assertEquals(List.of(animation), response.artifacts());
    }

    @Test
    void selectorFailsClosedWhenModeHasNoRegisteredStrategy() {
        AgentOrchestrator react = new ReActAgentOrchestrator(mock(GenerationExecutionPort.class));
        AgentOrchestratorSelector selector = new AgentOrchestratorSelector(List.of(react));
        assertEquals(react, selector.select(OrchestrationMode.REACT));
        assertThrows(IllegalStateException.class, () -> selector.select(OrchestrationMode.DEMO));
    }

    @Test
    void coordinatorSelectsConfiguredAndExplicitDemoModes() {
        GenerationRequestAssembler assembler = new GenerationRequestAssembler();
        AgentOrchestrator react = mock(AgentOrchestrator.class);
        AgentOrchestrator demo = mock(AgentOrchestrator.class);
        when(react.supports(OrchestrationMode.REACT)).thenReturn(true);
        when(demo.supports(OrchestrationMode.DEMO)).thenReturn(true);
        when(react.execute(any(), any())).thenReturn(
                new GenerationResult(response("PUBLISHED", "{}"), GenerationState.SUCCEEDED, "REACT_MULTI_AGENT"));
        when(demo.execute(any(), any())).thenReturn(
                new GenerationResult(response("PUBLISHED", "{\"origin\":\"DEMO\"}"), GenerationState.SUCCEEDED, "DEMO_MODE"));
        AgentOrchestratorSelector selector = new AgentOrchestratorSelector(List.of(react, demo));
        ResourceGenerationRequest request = new ResourceGenerationRequest(1L, "CNN", "{}", null, null, null);

        GenerationIdempotencyService idempotency = new GenerationIdempotencyService(
                Duration.ofMinutes(1), Duration.ofSeconds(5), 32, Clock.systemUTC());
        ResourceGenerationCoordinator online = new ResourceGenerationCoordinator(
                assembler, selector, idempotency, observability(), false, "react");
        assertEquals("run", online.generate(request).runId());
        verify(react).execute(any(), eq(null));

        ResourceGenerationCoordinator demoCoordinator = new ResourceGenerationCoordinator(
                assembler, selector, idempotency, observability(), true, "react");
        assertEquals("run", demoCoordinator.generate(request).runId());
        verify(demo).execute(any(), eq(null));
    }

    @Test
    void coordinatorRejectsFailedResultsAndInvalidModes() {
        GenerationRequestAssembler assembler = new GenerationRequestAssembler();
        AgentOrchestrator react = mock(AgentOrchestrator.class);
        when(react.supports(OrchestrationMode.REACT)).thenReturn(true);
        when(react.execute(any(), any())).thenReturn(
                new GenerationResult(null, GenerationState.FAILED, "REACT_MULTI_AGENT"));
        ResourceGenerationCoordinator coordinator = new ResourceGenerationCoordinator(
                assembler,
                new AgentOrchestratorSelector(List.of(react)),
                new GenerationIdempotencyService(
                        Duration.ofMinutes(1), Duration.ofSeconds(5), 32, Clock.systemUTC()),
                observability(),
                false,
                "react");
        ResourceGenerationRequest request = new ResourceGenerationRequest(1L, "CNN", "{}", null, null, null);

        assertThrows(IllegalStateException.class, () -> coordinator.generate(request));
        assertThrows(IllegalArgumentException.class,
                () -> new ResourceGenerationCoordinator(
                        assembler,
                        new AgentOrchestratorSelector(List.of()),
                        new GenerationIdempotencyService(
                                Duration.ofMinutes(1), Duration.ofSeconds(5), 32, Clock.systemUTC()),
                        observability(),
                        false,
                        "unknown"));
        assertThrows(IllegalArgumentException.class, () -> OrchestrationMode.fromConfiguration("unknown"));
        assertEquals(OrchestrationMode.REACT, OrchestrationMode.fromConfiguration(null));
        assertEquals(OrchestrationMode.WORKFLOW, OrchestrationMode.fromConfiguration("legacy"));
    }

    private static ResourceGenerationResponse response(String publishStatus, String contentJson) {
        GeneratedArtifact artifact = new GeneratedArtifact();
        artifact.setPublishStatus(publishStatus);
        artifact.setContentJson(contentJson);
        return new ResourceGenerationResponse("run", List.of(artifact), List.of(), "ok");
    }

    private static GeneratedArtifact artifact(ArtifactType type) {
        GeneratedArtifact artifact = new GeneratedArtifact();
        artifact.setArtifactType(type);
        artifact.setPublishStatus("PUBLISHED");
        artifact.setValidationStatus("GROUNDED");
        return artifact;
    }

    private static GenerationObservability observability() {
        return new GenerationObservability(new SimpleMeterRegistry(), ObservationRegistry.NOOP);
    }
}
