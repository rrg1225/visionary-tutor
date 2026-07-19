package com.visionary.rag.repository;

import com.visionary.rag.VectorDbService.KnowledgeFragment;

import java.util.List;

public record KnowledgeSearchOutcome(
        List<KnowledgeFragment> fragments,
        String retrievalMode,
        boolean highAvailabilityFallback
) {
    public static final String MODE_CHROMA = "CHROMA";
    public static final String MODE_BM25 = "BM25";
    public static final String MODE_HYBRID = "HYBRID";
    public static final String MODE_NONE = "NONE";

    public static KnowledgeSearchOutcome empty() {
        return new KnowledgeSearchOutcome(List.of(), MODE_NONE, false);
    }

    public static KnowledgeSearchOutcome chroma(List<KnowledgeFragment> fragments) {
        return new KnowledgeSearchOutcome(
                fragments == null ? List.of() : fragments,
                MODE_CHROMA,
                false
        );
    }

    public static KnowledgeSearchOutcome bm25Ha(List<KnowledgeFragment> fragments) {
        return new KnowledgeSearchOutcome(
                fragments == null ? List.of() : fragments,
                MODE_BM25,
                true
        );
    }

    public static KnowledgeSearchOutcome hybrid(List<KnowledgeFragment> fragments) {
        return new KnowledgeSearchOutcome(
                fragments == null ? List.of() : fragments,
                MODE_HYBRID,
                false
        );
    }
}
