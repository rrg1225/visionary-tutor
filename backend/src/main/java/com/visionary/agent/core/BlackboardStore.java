package com.visionary.agent.core;

import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Run-scoped registry for {@link SharedBlackboard} instances.
 * Ensures all agents participating in the same run share one blackboard object.
 */
@Service
public class BlackboardStore {

    private final ConcurrentHashMap<String, SharedBlackboard> store = new ConcurrentHashMap<>();

    public SharedBlackboard getOrCreate(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        return store.computeIfAbsent(runId, id -> {
            SharedBlackboard blackboard = new SharedBlackboard();
            blackboard.setRunId(id);
            return blackboard;
        });
    }

    public void remove(String runId) {
        if (runId != null && !runId.isBlank()) {
            store.remove(runId);
        }
    }
}
