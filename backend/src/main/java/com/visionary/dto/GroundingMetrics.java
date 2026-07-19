package com.visionary.dto;

/**
 * Explainable proxy metrics for RAG answer faithfulness and citation coverage.
 */
public record GroundingMetrics(
        double citationRatio,
        double overlapScore,
        RagContextRecord context
) {

    /** Citation coverage below this ratio is treated as high hallucination risk. */
    public static final double LOW_CITATION_RATIO = 0.15D;

    /** Lexical overlap below this score is treated as high hallucination risk. */
    public static final double LOW_OVERLAP_SCORE = 0.12D;

    /**
     * Returns {@code true} when proxy scores indicate the answer is weakly grounded
     * in retrieved evidence (potential hallucination).
     */
    public boolean isHighRiskOfHallucination() {
        if (context == null || context.retrievedDocs() <= 0) {
            return context != null && context.responseLength() > 160;
        }
        return citationRatio < LOW_CITATION_RATIO || overlapScore < LOW_OVERLAP_SCORE;
    }
}
