package com.visionary.platform.observability;

import com.visionary.dto.ResourceGenerationResponse;
import com.visionary.resourcegeneration.domain.GenerationState;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Metrics and tracing boundary for the resource generation use case. */
@Component
public class GenerationObservability {

    private final MeterRegistry meterRegistry;
    private final ObservationRegistry observationRegistry;

    public GenerationObservability(MeterRegistry meterRegistry, ObservationRegistry observationRegistry) {
        this.meterRegistry = meterRegistry;
        this.observationRegistry = observationRegistry;
    }

    public ResourceGenerationResponse observe(String mode, Supplier<ResourceGenerationResponse> generation) {
        Observation observation = Observation.createNotStarted("visionary.resource.generation", observationRegistry)
                .lowCardinalityKeyValue("mode", mode);
        Timer.Sample sample = Timer.start(meterRegistry);
        return observation.observe(() -> {
            try {
                ResourceGenerationResponse response = generation.get();
                int artifacts = response == null || response.artifacts() == null ? 0 : response.artifacts().size();
                meterRegistry.counter("visionary_generation_total", "mode", mode, "outcome", "success").increment();
                meterRegistry.summary("visionary_generation_artifacts", "mode", mode).record(artifacts);
                return response;
            } catch (RuntimeException failure) {
                meterRegistry.counter("visionary_generation_total", "mode", mode, "outcome", "failure").increment();
                observation.error(failure);
                throw failure;
            } finally {
                sample.stop(meterRegistry.timer("visionary_generation_duration", "mode", mode));
            }
        });
    }

    public void recordStage(GenerationState state, String agent, long latencyMs, Long tokenCost) {
        String stage = state == null ? "UNKNOWN" : state.name();
        String actor = agent == null || agent.isBlank() ? "unknown" : agent;
        meterRegistry.timer("visionary_generation_stage_duration", "stage", stage, "agent", actor)
                .record(Math.max(0L, latencyMs), TimeUnit.MILLISECONDS);
        if (tokenCost != null && tokenCost > 0L) {
            meterRegistry.counter("visionary_generation_tokens", "stage", stage, "agent", actor)
                    .increment(tokenCost);
        }
    }
}
