package com.visionary.rag;

import com.visionary.rag.CitationValidator.ValidationResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Shared lexical + semantic grounding audit used by chat SSE and /api/ai/invoke paths.
 */
@Service
@RequiredArgsConstructor
public class GroundingAuditService {

    private static final double REVIEW_THRESHOLD = 0.12D;

    private final CitationValidator citationValidator;
    private final RagGroundingScorer groundingScorer;

    public record GroundingAuditResult(
            String status,
            String message,
            double lexicalFaithfulnessScore,
            double semanticFaithfulnessScore,
            double faithfulnessScore,
            boolean needsReview
    ) {
    }

    public Optional<GroundingAuditResult> audit(String generated, RagRetrievalResult rag) {
        if (generated == null || generated.isBlank() || rag == null || !rag.hasGroundedEvidence()) {
            return Optional.empty();
        }
        ValidationResult validation = citationValidator.validate(generated, rag);
        double lexicalFaith = groundingScorer.faithfulnessAgainstCitations(generated, rag);
        double semanticFaith = groundingScorer.semanticFaithfulnessAgainstCitations(generated, rag);
        double combinedFaith = Math.max(lexicalFaith, semanticFaith);
        boolean needsReview = !"GROUNDED".equals(validation.status()) || combinedFaith < REVIEW_THRESHOLD;
        return Optional.of(new GroundingAuditResult(
                validation.status(),
                validation.message(),
                round(lexicalFaith),
                round(semanticFaith),
                round(combinedFaith),
                needsReview
        ));
    }

    public Map<String, Object> toPayload(GroundingAuditResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", result.status());
        payload.put("message", result.message());
        payload.put("lexicalFaithfulnessScore", result.lexicalFaithfulnessScore());
        payload.put("semanticFaithfulnessScore", result.semanticFaithfulnessScore());
        payload.put("faithfulnessScore", result.faithfulnessScore());
        payload.put("needsReview", result.needsReview());
        return payload;
    }

    private static double round(double value) {
        return Math.round(value * 1000D) / 1000D;
    }
}
