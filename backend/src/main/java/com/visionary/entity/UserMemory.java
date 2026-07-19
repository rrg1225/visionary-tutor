package com.visionary.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "user_memory")
public class UserMemory extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "learning_session_id")
    private Long learningSessionId;

    @Column(name = "memory_type", nullable = false, length = 32)
    private String memoryType;

    @Column(name = "memory_key", nullable = false, length = 128)
    private String memoryKey;

    @Column(name = "memory_value", nullable = false, columnDefinition = "TEXT")
    private String memoryValue;

    @Column(name = "source_type", nullable = false, length = 32)
    private String sourceType;

    @Column(name = "source_id")
    private Long sourceId;

    @Column(name = "priority", nullable = false)
    private Integer priority;

    @Column(name = "confidence_score", precision = 5, scale = 2)
    private BigDecimal confidenceScore;

    @Column(name = "review_status", nullable = false, length = 32)
    private String reviewStatus;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;
}
