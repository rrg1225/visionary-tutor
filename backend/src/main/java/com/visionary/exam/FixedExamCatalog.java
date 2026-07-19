package com.visionary.exam;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record FixedExamCatalog(
        String version,
        String reviewStatus,
        List<Paper> papers
) {
    public record Paper(
            String code,
            String title,
            String description,
            String topic,
            String difficulty,
            Integer durationMinutes,
            List<Question> questions
    ) {
    }

    public record Question(
            String id,
            Integer order,
            String type,
            String difficulty,
            List<String> knowledgePoints,
            String prompt,
            List<String> subQuestions,
            List<Option> options,
            String standardAnswer,
            List<ScoringPoint> scoringPoints,
            String explanation,
            List<String> commonErrors,
            Map<String, String> distractorAnalysis,
            List<Source> sources,
            String recommendedReview,
            String validationMethod,
            List<TestCase> testCases,
            BigDecimal maxScore
    ) {
    }

    public record Option(String key, String text) {
    }

    public record ScoringPoint(
            String id,
            String description,
            BigDecimal points,
            List<String> acceptedKeywords
    ) {
    }

    public record Source(String title, String url) {
    }

    public record TestCase(String input, String expected) {
    }
}
