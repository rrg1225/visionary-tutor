package com.visionary.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "agent_execution_log")
public class AgentExecutionLog extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "student_id", length = 64)
    private String studentId;

    @Column(name = "agent_role", nullable = false, length = 64)
    private String agentRole; // e.g., SupervisorAgent

    @Column(name = "thought", columnDefinition = "TEXT")
    private String thought;

    @Column(name = "action_name", length = 128)
    private String actionName;

    @Column(name = "action_input", columnDefinition = "TEXT")
    private String actionInput;

    @Column(name = "observation", columnDefinition = "TEXT")
    private String observation;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "IN_PROGRESS"; // IN_PROGRESS, COMPLETED, ERROR, MANUAL_REVIEW_REQUIRED

    @Column(name = "artifact_type", length = 64)
    private String artifactType; // 产物类型，如 HANDOUT, QUIZ, MINDMAP 等

    @Column(name = "fallback_reason", columnDefinition = "TEXT")
    private String fallbackReason; // 进入 Fallback 的原因说明

    @Column(name = "revision_round", nullable = false)
    private Integer revisionRound = 0; // 当前返修轮次

    @Column(name = "max_revision_rounds", nullable = false)
    private Integer maxRevisionRounds = 2; // 最大返修轮次

    @Column(name = "revision_status", length = 32)
    private String revisionStatus; // 返修状态：REVISING, COMPLETED, MANUAL_REVIEW_REQUIRED

    @Column(name = "reflection_reason", columnDefinition = "TEXT")
    private String reflectionReason; // CriticAgent 的反思理由

    @Column(name = "critic_verdict", length = 32)
    private String criticVerdict; // CriticAgent 的审查结果：PASS, REVISE, MANUAL_REVIEW_REQUIRED

    @Column(name = "factuality_score")
    private Double factualityScore; // 事实性得分
}