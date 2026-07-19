package com.visionary.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ProviderCircuitBreaker {

    private final Map<String, CircuitState> states = new ConcurrentHashMap<>();

    @Value("${ai.circuit-breaker.failure-threshold:3}")
    private int failureThreshold;

    @Value("${ai.circuit-breaker.reset-timeout-ms:30000}")
    private long resetTimeoutMs;

    public boolean allowRequest(String provider) {
        CircuitState state = states.computeIfAbsent(provider, ignored -> new CircuitState());
        if (!state.open) {
            return true;
        }
        if (System.currentTimeMillis() - state.openedAt >= resetTimeoutMs) {
            state.open = false;
            state.failures = 0;
            return true;
        }
        return false;
    }

    public void recordSuccess(String provider) {
        CircuitState state = states.computeIfAbsent(provider, ignored -> new CircuitState());
        state.failures = 0;
        state.open = false;
    }

    public void recordFailure(String provider) {
        CircuitState state = states.computeIfAbsent(provider, ignored -> new CircuitState());
        state.failures++;
        if (state.failures >= failureThreshold) {
            state.open = true;
            state.openedAt = System.currentTimeMillis();
        }
    }

    public void requireAvailable(String provider) throws IOException {
        if (!allowRequest(provider)) {
            throw new IOException("Circuit breaker OPEN for provider: " + provider);
        }
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        states.forEach((provider, state) -> snapshot.put(provider, Map.of(
                "status", state.open ? "OPEN" : "CLOSED",
                "failures", state.failures,
                "openedAt", state.openedAt == 0 ? "" : Instant.ofEpochMilli(state.openedAt).toString()
        )));
        return snapshot;
    }

    private static final class CircuitState {
        private volatile int failures;
        private volatile boolean open;
        private volatile long openedAt;
    }
}
