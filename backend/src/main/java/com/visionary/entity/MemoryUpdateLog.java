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
@Table(name = "memory_update_log")
public class MemoryUpdateLog extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "learning_session_id")
    private Long learningSessionId;

    @Column(name = "memory_id")
    private Long memoryId;

    @Column(name = "old_value", columnDefinition = "TEXT")
    private String oldValue;

    @Column(name = "new_value", nullable = false, columnDefinition = "TEXT")
    private String newValue;

    @Column(name = "update_reason", length = 512)
    private String updateReason;

    @Column(name = "source_text", columnDefinition = "TEXT")
    private String sourceText;

    @Column(name = "agent_score", precision = 5, scale = 2)
    private BigDecimal agentScore;

    @Column(name = "update_status", nullable = false, length = 32)
    private String updateStatus;
}
