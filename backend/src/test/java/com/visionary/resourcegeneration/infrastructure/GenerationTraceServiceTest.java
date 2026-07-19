package com.visionary.resourcegeneration.infrastructure;

import com.visionary.entity.GenerationEvent;
import com.visionary.repository.GenerationEventRepository;
import com.visionary.resourcegeneration.domain.GenerationState;
import com.visionary.resourcegeneration.domain.GenerationStateMachine;
import com.visionary.platform.observability.GenerationObservability;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

class GenerationTraceServiceTest {

    private final GenerationEventRepository repository = mock(GenerationEventRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-11T06:00:00Z"), ZoneOffset.UTC);
    private final GenerationTraceService service =
            new GenerationTraceService(
                    repository,
                    new GenerationStateMachine(),
                    clock,
                    new GenerationObservability(new SimpleMeterRegistry(), ObservationRegistry.NOOP)
            );
    private final List<GenerationEvent> stored = new ArrayList<>();

    @BeforeEach
    void persistMockEvents() {
        when(repository.save(any(GenerationEvent.class))).thenAnswer(invocation -> {
            GenerationEvent event = invocation.getArgument(0);
            stored.add(event);
            return event;
        });
        when(repository.existsByTraceId(anyString())).thenAnswer(invocation -> stored.stream()
                .anyMatch(event -> invocation.getArgument(0).equals(event.getTraceId())));
        when(repository.findFirstByTraceIdOrderByOccurredAtDesc(anyString())).thenAnswer(invocation -> {
            String traceId = invocation.getArgument(0);
            for (int index = stored.size() - 1; index >= 0; index--) {
                if (traceId.equals(stored.get(index).getTraceId())) {
                    return Optional.of(stored.get(index));
                }
            }
            return Optional.empty();
        });
    }

    @Test
    void persistsAnAuditableHappyPath() {
        service.start("trace-1", 17L, "react", "accepted");
        service.transition("trace-1", GenerationState.PLANNING, "PlannerAgent", "react", 10L, "plan");
        service.transition("trace-1", GenerationState.RETRIEVING, "RagRetrievalService", "react", 20L, "retrieve");
        service.transition("trace-1", GenerationState.GENERATING, "DocAgent", "deepseek", 30L, "generate");
        service.transition("trace-1", GenerationState.CRITIQUING, "CriticAgent", "deepseek", 40L, "critic");
        service.transition("trace-1", GenerationState.PERSISTING, "Persistence", "react", null, "persist");
        service.transition("trace-1", GenerationState.SUCCEEDED, "UseCase", "react", null, "done");

        ArgumentCaptor<GenerationEvent> captor = ArgumentCaptor.forClass(GenerationEvent.class);
        verify(repository, times(7)).save(captor.capture());
        List<GenerationEvent> events = captor.getAllValues();
        assertEquals(GenerationState.CREATED, events.get(0).getToState());
        assertEquals(GenerationState.SUCCEEDED, events.get(6).getToState());
        assertEquals("trace-1", events.get(6).getTraceId());
        assertEquals(17L, events.get(6).getLearningSessionId());
        assertEquals(0L, events.get(6).getLatencyMs());
    }

    @Test
    void rejectsDuplicateUnknownAndIllegalTransitions() {
        service.start("trace-2", 18L, "legacy", "accepted");
        assertThrows(IllegalStateException.class,
                () -> service.start("trace-2", 18L, "legacy", "duplicate"));
        assertThrows(IllegalStateException.class,
                () -> service.transition("missing", GenerationState.PLANNING, "Planner", "legacy", null, "missing"));
        assertThrows(IllegalStateException.class,
                () -> service.transition("trace-2", GenerationState.SUCCEEDED, "UseCase", "legacy", null, "skip"));
    }

    @Test
    void safeTransitionDoesNotBreakBusinessFlowWhenTraceIsUnavailable() {
        service.safeTransition("missing", GenerationState.FAILED, "UseCase", "react", "audit unavailable");
        verify(repository, times(0)).save(new GenerationEvent());
    }
}
