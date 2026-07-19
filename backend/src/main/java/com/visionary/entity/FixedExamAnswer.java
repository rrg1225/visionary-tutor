package com.visionary.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "fixed_exam_answer")
@Getter
@Setter
@NoArgsConstructor
public class FixedExamAnswer extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "attempt_id", nullable = false)
    private Long attemptId;

    @Column(name = "question_id", nullable = false, length = 96)
    private String questionId;

    @Column(name = "user_answer", columnDefinition = "LONGTEXT")
    private String userAnswer;

    @Column(precision = 8, scale = 2)
    private BigDecimal score;

    @Column(name = "max_score", nullable = false, precision = 8, scale = 2)
    private BigDecimal maxScore;

    @Column(name = "is_correct", nullable = false)
    private boolean correct;

    @Column(name = "is_draft", nullable = false)
    private boolean draft;

    @Column(name = "viewed_answer_before_submit", nullable = false)
    private boolean viewedAnswerBeforeSubmit;

    @Column(name = "duration_seconds", nullable = false)
    private int durationSeconds;

    @Column(name = "revision_count", nullable = false)
    private int revisionCount;

    @Column(name = "grading_json", columnDefinition = "LONGTEXT")
    private String gradingJson;
}
