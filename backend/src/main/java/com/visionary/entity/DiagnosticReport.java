package com.visionary.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "diagnostic_report")
public class DiagnosticReport extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "learning_session_id", nullable = false)
    private Long learningSessionId;

    @Column(name = "diagnosis_id", length = 64)
    private String diagnosisId;

    @Column(name = "reasoning_trace", columnDefinition = "TEXT")
    private String reasoningTrace;

    @Column(name = "rag_application_context", columnDefinition = "TEXT")
    private String ragApplicationContext;

    @Column(name = "rag_algorithm_context", columnDefinition = "TEXT")
    private String ragAlgorithmContext;

    @Column(name = "rag_math_context", columnDefinition = "TEXT")
    private String ragMathContext;

    @OneToMany(mappedBy = "diagnosticReport", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<DiagnosticWeakNode> weakNodes = new ArrayList<>();
}
