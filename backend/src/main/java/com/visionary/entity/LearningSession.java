package com.visionary.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "learning_session")
public class LearningSession extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "topic", nullable = false, length = 256)
    private String topic;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SessionStatus status = SessionStatus.ACTIVE;

    /**
     * Current phase in the closed-loop: profile -> diagnosis -> resource -> assessment.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "current_phase", length = 32)
    private LearningPhase currentPhase;

    @Column(name = "streaming_handout", columnDefinition = "LONGTEXT")
    private String streamingHandout;

    @Column(name = "conversation_summary", columnDefinition = "TEXT")
    private String conversationSummary;

    @Column(name = "last_emotion_snapshot", columnDefinition = "TEXT")
    private String lastEmotionSnapshot;

    @Column(name = "assessment_file_name", length = 256)
    private String assessmentFileName;

    public enum SessionStatus {
        ACTIVE,
        PAUSED,
        COMPLETED
    }

    public enum LearningPhase {
        STUDENT_PROFILE,
        KNOWLEDGE_DIAGNOSIS,
        RESOURCE_GENERATION,
        ASSESSMENT_FEEDBACK
    }
}
