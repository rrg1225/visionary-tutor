package com.visionary.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = "diagnosticReport")
@Entity
@Table(name = "diagnostic_weak_node")
public class DiagnosticWeakNode extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "diagnostic_report_id", nullable = false)
    private DiagnosticReport diagnosticReport;

    @Column(name = "node_name", nullable = false, length = 128)
    private String nodeName;

    @Enumerated(EnumType.STRING)
    @Column(name = "knowledge_layer", nullable = false, length = 16)
    private KnowledgeLayer knowledgeLayer;

    /**
     * Mastery score in [0, 100], aligned with frontend DiagnosticReport meter.
     */
    @Column(name = "mastery_score", nullable = false)
    private Integer masteryScore;

    public enum KnowledgeLayer {
        APPLICATION,
        ALGORITHM,
        MATH
    }
}
