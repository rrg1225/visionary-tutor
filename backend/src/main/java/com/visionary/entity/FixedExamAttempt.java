package com.visionary.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "fixed_exam_attempt")
@Getter
@Setter
@NoArgsConstructor
public class FixedExamAttempt extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "learning_session_id")
    private Long learningSessionId;

    @Column(name = "paper_code", nullable = false, length = 64)
    private String paperCode;

    @Column(name = "catalog_version", nullable = false, length = 32)
    private String catalogVersion;

    @Column(nullable = false, length = 24)
    private String status;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "total_score", precision = 8, scale = 2)
    private BigDecimal totalScore;

    @Column(name = "max_score", precision = 8, scale = 2)
    private BigDecimal maxScore;

    @Column(name = "total_duration_seconds", nullable = false)
    private int totalDurationSeconds;
}
