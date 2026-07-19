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

/**
 * Aggregated learning-state observation report (学习状态辅助).
 *
 * <p>Holds only locally aggregated indicators — sample count, duration, mean visual-load
 * score and the honest conclusion text. Raw video never leaves the browser.</p>
 */
@Entity
@Table(name = "learning_state_report")
@Getter
@Setter
@NoArgsConstructor
public class LearningStateReport extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "learning_session_id")
    private Long learningSessionId;

    @Column(name = "context_type", nullable = false, length = 64)
    private String contextType;

    @Column(name = "context_key", nullable = false, length = 191)
    private String contextKey;

    @Column(name = "context_title")
    private String contextTitle;

    @Column(name = "sample_count", nullable = false)
    private int sampleCount;

    @Column(name = "duration_seconds", nullable = false)
    private int durationSeconds;

    @Column(name = "aggregate_score")
    private Integer aggregateScore;

    @Column(name = "is_sufficient", nullable = false)
    private boolean sufficient;

    @Column(name = "headline", nullable = false)
    private String headline;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * Per-marker aggregates as JSON（如按题目 ID 分组的信号均值），用于题卷报告的
     * "困惑峰值 ↔ 失分题" 交叉展示。仍是聚合指标，不含原始帧。
     */
    @Column(name = "markers_json", columnDefinition = "TEXT")
    private String markersJson;
}
