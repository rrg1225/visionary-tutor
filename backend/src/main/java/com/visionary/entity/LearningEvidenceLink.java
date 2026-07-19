package com.visionary.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "learning_evidence_link")
@Getter
@Setter
public class LearningEvidenceLink {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "learning_session_id") private Long learningSessionId;
    @Column(name = "evidence_type", nullable = false, length = 32) private String evidenceType;
    @Column(name = "content_id", length = 128) private String contentId;
    @Column(name = "section_id", length = 128) private String sectionId;
    @Column(name = "paper_id", length = 128) private String paperId;
    @Column(name = "question_id", length = 128) private String questionId;
    @Column(name = "attempt_id", length = 128) private String attemptId;
    @Column(name = "state_report_id", length = 128) private String stateReportId;
    @Column(name = "ai_context_key") private String aiContextKey;
    @Column(name = "report_id", length = 128) private String reportId;
    @Column(name = "practice_id", length = 128) private String practiceId;
    @Column(name = "payload_json", columnDefinition = "LONGTEXT") private String payloadJson;
    @Column(name = "gmt_create", nullable = false, updatable = false) private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
