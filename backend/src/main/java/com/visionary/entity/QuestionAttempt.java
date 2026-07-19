package com.visionary.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "question_attempt")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuestionAttempt extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "learning_session_id")
    private Long learningSessionId;

    @Column(name = "source_type", length = 32)
    private String sourceType;

    @Column(name = "source_question_id", length = 96)
    private String sourceQuestionId;

    @Column(name = "fixed_exam_attempt_id")
    private Long fixedExamAttemptId;

    @Column(name = "question_key", nullable = false, length = 96)
    private String questionKey;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String prompt;

    @Column(name = "user_answer", columnDefinition = "TEXT")
    private String userAnswer;

    @Column(name = "correct_answer", columnDefinition = "TEXT")
    private String correctAnswer;

    @Column(columnDefinition = "TEXT")
    private String explanation;

    @Column(length = 128)
    private String concept;

    @Column(name = "is_correct", nullable = false)
    private boolean correct;

    @Column(nullable = false)
    private boolean skipped;

    @Column(name = "viewed_answer_before_submit", nullable = false)
    private boolean viewedAnswerBeforeSubmit;

    @Column(name = "duration_seconds", nullable = false)
    private int durationSeconds;

    @Column(name = "review_status", nullable = false, length = 24)
    private String reviewStatus;

    @Column(name = "review_count", nullable = false)
    private int reviewCount;

    @Column(name = "next_review_at")
    private LocalDateTime nextReviewAt;

    @Column(name = "last_reviewed_at")
    private LocalDateTime lastReviewedAt;
}
