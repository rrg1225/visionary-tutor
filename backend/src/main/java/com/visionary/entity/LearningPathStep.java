package com.visionary.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "learning_path_step")
public class LearningPathStep extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "learning_session_id", nullable = false)
    private Long learningSessionId;

    @Column(name = "path_node_id")
    private Long pathNodeId;

    @Column(name = "artifact_id")
    private Long artifactId;

    @Column(name = "step_order", nullable = false)
    private Integer stepOrder;

    @Column(name = "step_title", nullable = false, length = 255)
    private String stepTitle;

    @Column(name = "step_goal", columnDefinition = "TEXT")
    private String stepGoal;

    @Column(name = "recommended_resource_ids", columnDefinition = "TEXT")
    private String recommendedResourceIds;

    @Column(name = "estimated_minutes")
    private Integer estimatedMinutes;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "time_spent_seconds")
    private Integer timeSpentSeconds;
}
