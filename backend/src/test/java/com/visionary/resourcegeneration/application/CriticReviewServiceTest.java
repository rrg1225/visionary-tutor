package com.visionary.resourcegeneration.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.client.DeepSeekApiClient;
import com.visionary.dto.ResourceGenerationRequest;
import com.visionary.entity.GeneratedArtifact.ArtifactType;
import com.visionary.rag.CitationValidator;
import com.visionary.rag.RagRetrievalResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CriticReviewServiceTest {

    private final DeepSeekApiClient client = mock(DeepSeekApiClient.class);
    private final CriticReviewService service = new CriticReviewService(client, new ObjectMapper());
    private final ResourceGenerationRequest request =
            new ResourceGenerationRequest(1L, "CNN", "visual learner", "padding", null, null);

    @Test
    void citationVerdictIsTheDeterministicFallbackWithoutLlm() {
        when(client.isConfigured()).thenReturn(false);
        CriticReviewDecision grounded = service.critique(
                ArtifactType.HANDOUT, "CNN", "content", request, validation("GROUNDED"), RagRetrievalResult.empty());
        CriticReviewDecision unsupported = service.critique(
                ArtifactType.HANDOUT, "CNN", "content", request, validation("NO_EVIDENCE"), RagRetrievalResult.empty());
        CriticReviewDecision invalidCitation = service.critique(
                ArtifactType.HANDOUT, "CNN", "content", request, validation("INVALID_CITATION"), RagRetrievalResult.empty());
        assertFalse(grounded.needsRevision());
        assertFalse(unsupported.needsRevision());
        assertTrue(invalidCitation.needsRevision());
        assertTrue(service.replan("CNN", ArtifactType.HANDOUT, "DocAgent", "plan", List.of(), invalidCitation,
                request, RagRetrievalResult.empty()).contains("返修约束"));
    }

    @Test
    void factualityAndCriticDecisionsAreCombined() throws Exception {
        when(client.isConfigured()).thenReturn(true);
        when(client.chat(anyString(), anyString(), anyBoolean()))
                .thenReturn("prefix {\"factualityScore\":0.95,\"factualErrors\":[],\"hallucinationLog\":[]} suffix")
                .thenReturn("{\"verdict\":\"PASS\",\"critique\":\"ok\"}");
        CriticReviewDecision decision = service.critique(
                ArtifactType.QUIZ, "CNN", "content", request, validation("GROUNDED"), RagRetrievalResult.empty());
        assertFalse(decision.needsRevision());
        assertTrue(decision.message().contains("CriticReport"));
    }

    @Test
    void lowFactualityTriggersRevisionAndRepairFailuresKeepOriginal() throws Exception {
        when(client.isConfigured()).thenReturn(true);
        when(client.chat(anyString(), anyString(), anyBoolean()))
                .thenReturn("{\"factualityScore\":0.5,\"factualErrors\":[\"error\"],\"hallucinationLog\":[\"hallucination\"]}")
                .thenReturn("{\"verdict\":\"PASS\",\"critique\":\"review\"}")
                .thenThrow(new IllegalStateException("repair unavailable"));
        CriticReviewDecision decision = service.critique(
                ArtifactType.VISUALIZATION, "CNN", "content", request, validation("GROUNDED"), RagRetrievalResult.empty());
        assertTrue(decision.needsRevision());
        assertTrue(decision.message().contains("Factuality"));
        assertEquals("original", service.repair(
                ArtifactType.VISUALIZATION, "CNN", "original", "review", "plan", RagRetrievalResult.empty()));
    }

    private static CitationValidator.ValidationResult validation(String status) {
        return new CitationValidator.ValidationResult(status, "validation");
    }
}
