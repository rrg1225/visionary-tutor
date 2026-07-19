package com.visionary.resourcegeneration.application;

import com.visionary.dto.ResourceGenerationResponse;
import com.visionary.entity.GeneratedArtifact.ArtifactType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenerationIdempotencyServiceTest {

    private final GenerationIdempotencyService service = new GenerationIdempotencyService(
            Duration.ofMinutes(1), Duration.ofSeconds(2), 32, Clock.systemUTC());

    @Test
    void reusesSuccessfulResultForTheSameExplicitRequestId() {
        AtomicInteger calls = new AtomicInteger();
        GenerationCommand command = command("request-1");

        ResourceGenerationResponse first = service.execute(command, () -> response("run-" + calls.incrementAndGet()));
        ResourceGenerationResponse second = service.execute(command, () -> response("run-" + calls.incrementAndGet()));

        assertEquals("run-1", first.runId());
        assertEquals("run-1", second.runId());
        assertEquals(1, calls.get());
    }

    @Test
    void missingRequestIdAlwaysCreatesANewRunAndFailuresCanBeRetried() {
        AtomicInteger calls = new AtomicInteger();
        assertEquals("run-1", service.execute(command(null), () -> response("run-" + calls.incrementAndGet())).runId());
        assertEquals("run-2", service.execute(command(null), () -> response("run-" + calls.incrementAndGet())).runId());

        assertThrows(IllegalStateException.class,
                () -> service.execute(command("retryable"), () -> {
                    throw new IllegalStateException("temporary");
                }));
        assertEquals("recovered", service.execute(command("retryable"), () -> response("recovered")).runId());
    }

    private static GenerationCommand command(String requestId) {
        return new GenerationCommand(7L, "CNN", "{}", "padding", null, List.of(ArtifactType.HANDOUT), requestId);
    }

    private static ResourceGenerationResponse response(String runId) {
        return new ResourceGenerationResponse(runId, List.of(), List.of(), "ok");
    }
}
