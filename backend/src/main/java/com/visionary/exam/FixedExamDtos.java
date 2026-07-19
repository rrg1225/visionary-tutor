package com.visionary.exam;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class FixedExamDtos {

    private FixedExamDtos() {
    }

    public record PaperSummary(
            String code,
            String title,
            String description,
            String topic,
            String difficulty,
            int durationMinutes,
            int questionCount,
            BigDecimal maxScore,
            String catalogVersion,
            String reviewStatus
    ) {
    }

    public record PaperView(
            PaperSummary summary,
            List<QuestionView> questions
    ) {
    }

    public record QuestionView(
            String id,
            int order,
            String type,
            String difficulty,
            List<String> knowledgePoints,
            String prompt,
            List<String> subQuestions,
            List<FixedExamCatalog.Option> options,
            BigDecimal maxScore
    ) {
    }

    public record StartAttemptRequest(Long learningSessionId) {
    }

    public record SaveAnswerRequest(
            String userAnswer,
            Integer durationSeconds
    ) {
    }

    public record AttemptView(
            Long id,
            String paperCode,
            String catalogVersion,
            String status,
            LocalDateTime startedAt,
            LocalDateTime submittedAt,
            int totalDurationSeconds,
            List<AnswerDraft> answers
    ) {
    }

    public record AnswerDraft(
            String questionId,
            String userAnswer,
            boolean answered,
            boolean viewedAnswerBeforeSubmit,
            int durationSeconds,
            int revisionCount
    ) {
    }

    public record AnswerReview(
            String questionId,
            String standardAnswer,
            List<FixedExamCatalog.ScoringPoint> scoringPoints,
            String explanation,
            List<String> commonErrors,
            Map<String, String> distractorAnalysis,
            List<FixedExamCatalog.Source> sources,
            String recommendedReview,
            String validationMethod,
            List<FixedExamCatalog.TestCase> testCases
    ) {
    }

    public record QuestionResult(
            String questionId,
            int order,
            String prompt,
            String type,
            List<String> knowledgePoints,
            String userAnswer,
            BigDecimal score,
            BigDecimal maxScore,
            boolean correct,
            boolean unanswered,
            boolean reviewRecommended,
            boolean viewedAnswerBeforeSubmit,
            int durationSeconds,
            String standardAnswer,
            List<ScoringPointResult> scoringPoints,
            String explanation,
            List<String> commonErrors,
            Map<String, String> distractorAnalysis,
            List<FixedExamCatalog.Source> sources,
            String recommendedReview,
            String validationMethod,
            List<FixedExamCatalog.TestCase> testCases
    ) {
    }

    public record ScoringPointResult(
            String id,
            String description,
            BigDecimal points,
            boolean achieved
    ) {
    }

    public record MasteryResult(
            String knowledgePoint,
            BigDecimal earnedScore,
            BigDecimal maxScore,
            int masteryPercent
    ) {
    }

    public record ExamReportView(
            Long reportId,
            Long attemptId,
            String paperCode,
            String paperTitle,
            BigDecimal totalScore,
            BigDecimal maxScore,
            int accuracyPercent,
            int totalDurationSeconds,
            int answeredCount,
            int unansweredCount,
            List<String> viewedAnswerQuestionIds,
            List<QuestionResult> questions,
            List<MasteryResult> mastery,
            List<String> typicalErrors,
            List<String> recommendedReviews,
            LocalDateTime submittedAt
    ) {
    }
}
