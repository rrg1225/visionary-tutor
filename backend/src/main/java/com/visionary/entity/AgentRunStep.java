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
@Table(name = "agent_run_step")
public class AgentRunStep extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;

    @Column(name = "learning_session_id", nullable = false)
    private Long learningSessionId;

    @Column(name = "agent_name", nullable = false, length = 64)
    private String agentName;

    @Column(name = "step_order", nullable = false)
    private Integer stepOrder;

    @Column(name = "input_summary", columnDefinition = "TEXT")
    private String inputSummary;

    @Column(name = "output_summary", columnDefinition = "TEXT")
    private String outputSummary;

    @Column(name = "critique", columnDefinition = "TEXT")
    private String critique;

    @Column(name = "audit_trace_json", columnDefinition = "TEXT")
    private String auditTraceJson;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "COMPLETED";
}
