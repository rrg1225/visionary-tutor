package com.visionary.agent.core;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central shared state for the multi-agent run.
 * Holds structured profile, knowledge state, run trace, and vector memory reference.
 */
public class SharedBlackboard {

    private String learnerProfileSnapshot;
    private Map<String, Object> knowledgeState = new ConcurrentHashMap<>();
    private List<AgentRunTrace> runTrace = new java.util.concurrent.CopyOnWriteArrayList<>();
    private String currentTopic;

    // P2 Debate Phase + Safety
    private List<String> debateLog = new java.util.concurrent.CopyOnWriteArrayList<>();
    private Map<String, Object> safetyFlags = new ConcurrentHashMap<>(); // e.g. "content_safety": "PASSED", "hallucination_risk": 0.12
    private List<String> hallucinationLog = new java.util.concurrent.CopyOnWriteArrayList<>();
    private int debateRound = 0;
    private String runId;
    private final Map<String, Object> agentState = new ConcurrentHashMap<>();

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public void put(String key, Object value) {
        if (key != null && value != null) {
            agentState.put(key, value);
        }
    }

    public Object get(String key) {
        return agentState.get(key);
    }

    public java.util.Set<String> keySet() {
        return agentState.keySet();
    }

    public String getLearnerProfileSnapshot() {
        return learnerProfileSnapshot;
    }

    public void updateProfileSnapshot(String snapshot) {
        this.learnerProfileSnapshot = snapshot;
    }

    public Map<String, Object> getKnowledgeState() {
        return knowledgeState;
    }

    public void putKnowledgeState(String concept, Object state) {
        knowledgeState.put(concept, state);
    }

    public List<AgentRunTrace> getRunTrace() {
        return runTrace;
    }

    public void addTrace(AgentRunTrace trace) {
        runTrace.add(trace);
    }

    public String getCurrentTopic() {
        return currentTopic;
    }

    public void setCurrentTopic(String topic) {
        this.currentTopic = topic;
    }

    // === Debate Phase & Safety (P2) ===
    public List<String> getDebateLog() {
        return debateLog;
    }

    public void addDebateEntry(String entry) {
        debateLog.add(entry);
        addTrace(new AgentRunTrace("DebatePhase", "log", entry, java.time.Instant.now()));
    }

    public Map<String, Object> getSafetyFlags() {
        return safetyFlags;
    }

    public void putSafetyFlag(String key, Object value) {
        safetyFlags.put(key, value);
    }

    public List<String> getHallucinationLog() {
        return hallucinationLog;
    }

    public void addHallucinationEntry(String entry) {
        hallucinationLog.add(entry);
    }

    // === Debate Round Counter ===
    public int getDebateRound() {
        return debateRound;
    }

    public void incrementDebateRound() {
        this.debateRound++;
    }

    public record AgentRunTrace(String agentRole, String action, String summary, java.time.Instant timestamp) {}
}