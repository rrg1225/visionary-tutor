package com.visionary.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * Per-user, per-knowledge-point aggregate for dynamic knowledge profiling.
 * Updated as the learner practices; {@link #confidenceScore} is derived by
 * {@link com.visionary.service.KnowledgeTracingService#calculateConfidence(LearningMetrics)}.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(
        name = "knowledge_tracing_metrics",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_knowledge_tracing_user_concept",
                columnNames = {"user_id", "knowledge_concept"}
        )
)
public class LearningMetrics extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "knowledge_concept", nullable = false, length = 128)
    private String knowledgeConcept;

    @Column(name = "total_attempts", nullable = false)
    private Integer totalAttempts;

    @Column(name = "correct_count", nullable = false)
    private Integer correctCount;

    @Column(name = "last_practiced_at")
    private LocalDateTime lastPracticedAt;

    @Column(name = "confidence_score")
    private Double confidenceScore;
}
