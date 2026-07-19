package com.visionary.entity;

import com.visionary.resourcegeneration.domain.GenerationState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "generation_event", indexes = {
        @Index(name = "idx_generation_event_trace", columnList = "trace_id, occurred_at"),
        @Index(name = "idx_generation_event_session", columnList = "learning_session_id, occurred_at")
})
public class GenerationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trace_id", nullable = false, length = 64)
    private String traceId;

    @Column(name = "learning_session_id", nullable = false)
    private Long learningSessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_state", length = 32)
    private GenerationState fromState;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_state", nullable = false, length = 32)
    private GenerationState toState;

    @Column(name = "agent", length = 64)
    private String agent;

    @Column(name = "model", length = 128)
    private String model;

    @Column(name = "prompt_version", length = 64)
    private String promptVersion;

    @Column(name = "latency_ms", nullable = false)
    private Long latencyMs;

    @Column(name = "token_cost")
    private Long tokenCost;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    public Long getId() { return id; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public Long getLearningSessionId() { return learningSessionId; }
    public void setLearningSessionId(Long learningSessionId) { this.learningSessionId = learningSessionId; }
    public GenerationState getFromState() { return fromState; }
    public void setFromState(GenerationState fromState) { this.fromState = fromState; }
    public GenerationState getToState() { return toState; }
    public void setToState(GenerationState toState) { this.toState = toState; }
    public String getAgent() { return agent; }
    public void setAgent(String agent) { this.agent = agent; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }
    public Long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Long latencyMs) { this.latencyMs = latencyMs; }
    public Long getTokenCost() { return tokenCost; }
    public void setTokenCost(Long tokenCost) { this.tokenCost = tokenCost; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime occurredAt) { this.occurredAt = occurredAt; }
}
