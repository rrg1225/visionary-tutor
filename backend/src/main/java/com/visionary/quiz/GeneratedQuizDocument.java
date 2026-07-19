package com.visionary.quiz;

import java.util.List;

/**
 * Structured schema for AI-generated practice quizzes.
 *
 * <p>Shares the question vocabulary of {@code FixedExamCatalog} (types, scoring points,
 * explanations) so that generated practice and curated papers grade the same way, while
 * staying lighter: generated quizzes carry no manual review metadata, sources come from
 * the RAG citations persisted alongside the artifact.</p>
 */
public record GeneratedQuizDocument(
        String schema,
        String topic,
        String difficulty,
        List<Question> questions
) {

    public static final String SCHEMA_V1 = "generated-quiz/v1";

    public static final List<String> QUESTION_TYPES = List.of(
            "SINGLE_CHOICE", "SHORT_ANSWER", "CALCULATION", "CODE_DEBUGGING", "MULTI_STEP"
    );

    public record Question(
            String id,
            Integer order,
            String type,
            String difficulty,
            List<String> knowledgePoints,
            String prompt,
            List<Option> options,
            String standardAnswer,
            List<ScoringPoint> scoringPoints,
            String explanation,
            List<String> commonErrors,
            String recommendedReview
    ) {
    }

    public record Option(String key, String text) {
    }

    public record ScoringPoint(String description, List<String> acceptedKeywords) {
    }
}
