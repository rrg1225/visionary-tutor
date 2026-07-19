package com.visionary.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "resource_usage_record")
public class ResourceUsageRecord extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "learning_session_id")
    private Long learningSessionId;

    @Column(name = "resource_id")
    private Long resourceId;

    @Column(name = "action_type", nullable = false, length = 32)
    private String actionType;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "feedback", columnDefinition = "TEXT")
    private String feedback;
}
