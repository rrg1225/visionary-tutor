package com.visionary.os;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.rag.CitationValidator;
import com.visionary.rag.RagGroundingScorer;
import com.visionary.rag.RagRetrievalResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublishGateTest {

    private final RagGroundingScorer scorer = mock(RagGroundingScorer.class);
    private final PublishGate gate = new PublishGate(new ObjectMapper(), scorer);

    @Test
    void modelOnlyContentIsPublishableWithoutRagEvidence() {
        when(scorer.faithfulnessAgainstCitations(anyString(), any())).thenReturn(0.0D);
        when(scorer.semanticFaithfulnessAgainstCitations(anyString(), any())).thenReturn(0.0D);

        PublishGate.PublishDecision decision = gate.evaluate(
                "模型直接生成的完整教学内容",
                RagRetrievalResult.empty(),
                new CitationValidator.ValidationResult("NO_EVIDENCE", "未使用知识库补充材料")
        );

        assertEquals(PublishStatus.PUBLISHED, decision.publishStatus());
    }

    @Test
    void inventedCitationRemainsBlocked() {
        PublishGate.PublishDecision decision = gate.evaluate(
                "包含虚构引用 cite-missing 的内容",
                RagRetrievalResult.empty(),
                new CitationValidator.ValidationResult("INVALID_CITATION", "发现虚构引用")
        );

        assertEquals(PublishStatus.BLOCKED, decision.publishStatus());
    }
}
