package com.visionary.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagGroundingScorerTest {

    private final RagGroundingScorer scorer = new RagGroundingScorer(null);

    @Test
    void diagnosticCoverageAcceptsOnlyAnchorsFromReturnedCitations() {
        RagRetrievalResult result = resultWithCitations("cite-alpha", "cite-beta");

        assertThat(scorer.citationAnchorCoverage(
                "[cite-alpha] first fragment\n[cite-beta] second fragment",
                result
        )).isEqualTo(1D);
        assertThat(scorer.citationAnchorCoverage(
                "[cite-alpha] first fragment\n[cite-invented] unsupported fragment",
                result
        )).isEqualTo(0.5D);
    }

    @Test
    void diagnosticCoverageRequiresAnExplicitCitationAnchor() {
        assertThat(scorer.citationAnchorCoverage(
                "context without an anchor",
                resultWithCitations("cite-alpha")
        )).isZero();
    }

    private RagRetrievalResult resultWithCitations(String... citationIds) {
        List<RagCitation> citations = java.util.Arrays.stream(citationIds)
                .map(id -> new RagCitation(
                        id, "source.md", "concept_layer", "concept_layer", 0.9D,
                        "excerpt", id, "source.md", 0, 0, 10
                ))
                .toList();
        return new RagRetrievalResult(
                "", "", "", "", citations, true, "bm25_ha", true
        );
    }
}
