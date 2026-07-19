package com.visionary.resourcegeneration.infrastructure;

import com.visionary.entity.GenerationEvent;
import com.visionary.repository.GenerationEventRepository;
import com.visionary.resourcegeneration.domain.GenerationState;
import com.visionary.resourcegeneration.domain.GenerationStateMachine;
import com.visionary.platform.observability.GenerationObservability;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;

@Slf4j
@Service
public class GenerationTraceService {

    private static final String PROMPT_VERSION = "resource-generation-v1";

    private final GenerationEventRepository repository;
    private final GenerationStateMachine stateMachine;
    private final Clock clock;
    private final GenerationObservability observability;

    @Autowired
    public GenerationTraceService(
            GenerationEventRepository repository,
            GenerationObservability observability
    ) {
        this(repository, new GenerationStateMachine(), Clock.systemUTC(), observability);
    }

    GenerationTraceService(
            GenerationEventRepository repository,
            GenerationStateMachine stateMachine,
            Clock clock,
            GenerationObservability observability
    ) {
        this.repository = repository;
        this.stateMachine = stateMachine;
        this.clock = clock;
        this.observability = observability;
    }

    @Transactional
    public void start(String traceId, Long learningSessionId, String model, String reason) {
        if (repository.existsByTraceId(traceId)) {
            throw new IllegalStateException("Generation trace already exists: " + traceId);
        }
        Instant now = clock.instant();
        persist(traceId, learningSessionId, null, GenerationState.CREATED,
                "ResourceGenerationUseCase", model, 0L, null, reason, now);
    }

    @Transactional
    public void transition(
            String traceId,
            GenerationState next,
            String agent,
            String model,
            Long tokenCost,
            String reason
    ) {
        GenerationEvent current = repository.findFirstByTraceIdOrderByOccurredAtDesc(traceId)
                .orElseThrow(() -> new IllegalStateException("Unknown generation trace: " + traceId));
        stateMachine.requireTransition(current.getToState(), next);
        Instant now = clock.instant();
        Instant changedAt = current.getOccurredAt().atZone(clock.getZone()).toInstant();
        long latencyMs = Math.max(0L, Duration.between(changedAt, now).toMillis());
        persist(traceId, current.getLearningSessionId(), current.getToState(), next,
                agent, model, latencyMs, tokenCost, reason, now);
        observability.recordStage(next, agent, latencyMs, tokenCost);
    }

    @Transactional(readOnly = true)
    public GenerationState currentState(String traceId) {
        return repository.findFirstByTraceIdOrderByOccurredAtDesc(traceId)
                .map(GenerationEvent::getToState)
                .orElseThrow(() -> new IllegalStateException("Unknown generation trace: " + traceId));
    }

    public void safeTransition(
            String traceId,
            GenerationState next,
            String agent,
            String model,
            String reason
    ) {
        try {
            transition(traceId, next, agent, model, null, reason);
        } catch (RuntimeException exception) {
            log.warn("Generation trace transition was not persisted: traceId={}, next={}, error={}",
                    traceId, next, exception.getMessage());
        }
    }

    private void persist(
            String traceId,
            Long learningSessionId,
            GenerationState from,
            GenerationState to,
            String agent,
            String model,
            long latencyMs,
            Long tokenCost,
            String reason,
            Instant occurredAt
    ) {
        GenerationEvent event = new GenerationEvent();
        event.setTraceId(traceId);
        event.setLearningSessionId(learningSessionId);
        event.setFromState(from);
        event.setToState(to);
        event.setAgent(agent);
        event.setModel(model);
        event.setPromptVersion(PROMPT_VERSION);
        event.setLatencyMs(latencyMs);
        event.setTokenCost(tokenCost);
        event.setReason(reason);
        event.setOccurredAt(LocalDateTime.ofInstant(occurredAt, clock.getZone()));
        repository.save(event);
    }
}
