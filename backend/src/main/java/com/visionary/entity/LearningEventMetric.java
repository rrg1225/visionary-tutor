package com.visionary.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Append-only event log for learning-effect metrics (quiz accuracy, mastery deltas, etc.).
 */
@Entity
@Table(name = "learning_metrics")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LearningEventMetric extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "learning_session_id")
    private Long learningSessionId;

    @Column(name = "metric_type", nullable = false, length = 32)
    private String metricType;

    @Column(name = "concept", length = 128)
    private String concept;

    @Column(name = "value_numeric")
    private Double valueNumeric;

    @Column(name = "value_text", columnDefinition = "TEXT")
    private String valueText;

    @Column(name = "before_value")
    private Double beforeValue;

    @Column(name = "after_value")
    private Double afterValue;

    @Column(name = "event_time", nullable = false)
    private Instant eventTime;

    @Column(name = "source", length = 64)
    private String source;
}
