package com.visionary.resourcegeneration.application;

import com.visionary.dto.ResourceGenerationResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Collapses concurrent retries with the same client request id into one generation run.
 * Entries are intentionally short lived: callers must provide a new request id when they
 * want a new set of resources for otherwise identical input.
 */
@Service
public class GenerationIdempotencyService {

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Duration retention;
    private final Duration waitTimeout;
    private final int maximumEntries;
    private final Clock clock;

    @Autowired
    public GenerationIdempotencyService(
            @Value("${agent.generation.idempotency-retention-minutes:15}") long retentionMinutes,
            @Value("${agent.generation.total-timeout-seconds:240}") long totalTimeoutSeconds,
            @Value("${agent.generation.idempotency-maximum-entries:1000}") int maximumEntries
    ) {
        this(
                Duration.ofMinutes(Math.max(1L, retentionMinutes)),
                Duration.ofSeconds(Math.max(1L, totalTimeoutSeconds)),
                Math.max(32, maximumEntries),
                Clock.systemUTC()
        );
    }

    GenerationIdempotencyService(Duration retention, Duration waitTimeout, int maximumEntries, Clock clock) {
        this.retention = retention;
        this.waitTimeout = waitTimeout;
        this.maximumEntries = maximumEntries;
        this.clock = clock;
    }

    public ResourceGenerationResponse execute(
            GenerationCommand command,
            Supplier<ResourceGenerationResponse> generation
    ) {
        String key = key(command);
        if (key == null) {
            return generation.get();
        }
        evictExpired();
        Entry candidate = new Entry(new CompletableFuture<>(), clock.instant());
        Entry owner = entries.putIfAbsent(key, candidate);
        if (owner == null) {
            try {
                ResourceGenerationResponse response = generation.get();
                candidate.response().complete(response);
                enforceBound();
                return response;
            } catch (Throwable failure) {
                candidate.response().completeExceptionally(failure);
                entries.remove(key, candidate);
                throw failure;
            }
        }
        try {
            return owner.response().get(waitTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for idempotent generation", interrupted);
        } catch (TimeoutException timeout) {
            throw new IllegalStateException("Timed out waiting for idempotent generation: " + key, timeout);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Idempotent generation failed: " + key, cause);
        }
    }

    private String key(GenerationCommand command) {
        if (command.requestId() == null) {
            return null;
        }
        String types = command.resourceTypes().stream()
                .sorted(Comparator.comparing(Enum::name))
                .map(Enum::name)
                .reduce((left, right) -> left + "," + right)
                .orElse("ALL");
        return command.learningSessionId() + ":" + command.requestId() + ":" + types;
    }

    private void evictExpired() {
        Instant threshold = clock.instant().minus(retention);
        entries.entrySet().removeIf(entry -> entry.getValue().createdAt().isBefore(threshold)
                && entry.getValue().response().isDone());
    }

    private void enforceBound() {
        int overflow = entries.size() - maximumEntries;
        if (overflow <= 0) {
            return;
        }
        entries.entrySet().stream()
                .filter(entry -> entry.getValue().response().isDone())
                .sorted(Map.Entry.comparingByValue(Comparator.comparing(Entry::createdAt)))
                .limit(overflow)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(entries::remove);
    }

    private record Entry(CompletableFuture<ResourceGenerationResponse> response, Instant createdAt) {
    }
}
