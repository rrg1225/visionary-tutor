package com.visionary.os;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.rag.CitationValidator;
import com.visionary.rag.RagGroundingScorer;
import com.visionary.rag.RagRetrievalResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class PublishGate {

    private static final double SEMANTIC_PUBLISH_THRESHOLD = 0.72D;
    private static final double SEMANTIC_DEGRADED_THRESHOLD = 0.45D;

    private final ObjectMapper objectMapper;
    private final RagGroundingScorer groundingScorer;

    public PublishDecision evaluate(String content, RagRetrievalResult rag, CitationValidator.ValidationResult validation) {
        double lexicalFaith = groundingScorer.faithfulnessAgainstCitations(content, rag);
        double semanticFaith = groundingScorer.semanticFaithfulnessAgainstCitations(content, rag);
        double faithfulness = Math.max(lexicalFaith, semanticFaith);

        PublishStatus status = resolveStatus(validation.status(), faithfulness);

        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("validationStatus", validation.status());
        audit.put("validationMessage", validation.message());
        audit.put("lexicalFaithfulness", lexicalFaith);
        audit.put("semanticFaithfulness", semanticFaith);
        audit.put("faithfulnessEstimate", faithfulness);
        audit.put("citationCount", rag != null && rag.citations() != null ? rag.citations().size() : 0);
        audit.put("publishStatus", status.name());
        audit.put("groundingMode", semanticFaith > lexicalFaith ? "embedding" : "lexical");

        String auditJson;
        try {
            auditJson = objectMapper.writeValueAsString(audit);
        } catch (Exception e) {
            auditJson = "{\"error\":\"audit serialization failed\"}";
        }

        return new PublishDecision(status, validation.status(), validation.message(), faithfulness, auditJson);
    }

    private PublishStatus resolveStatus(String validationStatus, double faithfulness) {
        if ("INVALID_CITATION".equals(validationStatus)) {
            return PublishStatus.BLOCKED;
        }
        if ("GROUNDED".equals(validationStatus)
                || "NO_EVIDENCE".equals(validationStatus)
                || "RAG_UNUSED".equals(validationStatus)
                || faithfulness >= SEMANTIC_PUBLISH_THRESHOLD) {
            return PublishStatus.PUBLISHED;
        }
        if (faithfulness >= SEMANTIC_DEGRADED_THRESHOLD) {
            return PublishStatus.DEGRADED;
        }
        return PublishStatus.fromValidation(validationStatus);
    }

    public record PublishDecision(
            PublishStatus publishStatus,
            String validationStatus,
            String validationMessage,
            double faithfulnessEstimate,
            String auditJson
    ) {}
}
