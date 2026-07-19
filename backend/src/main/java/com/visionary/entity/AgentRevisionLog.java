package com.visionary.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "agent_revision_log")
public class AgentRevisionLog extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "artifact_id", nullable = false)
    private Long artifactId;

    @Column(name = "revision_round", nullable = false)
    private Integer revisionRound;

    @Column(name = "composite_score", nullable = false)
    private Double compositeScore;

    @Column(name = "score_delta", nullable = false)
    private Double scoreDelta;

    @Enumerated(EnumType.STRING)
    @Column(name = "breaker_decision", nullable = false, length = 32)
    private BreakerDecision breakerDecision;

    @Column(name = "critic_feedback", columnDefinition = "TEXT")
    private String criticFeedback;

    public enum BreakerDecision {
        CONTINUE,
        ACCEPT,
        BREAK_MAX_ROUNDS,
        BREAK_NO_GAIN,
        BREAK_MANUAL
    }
}
