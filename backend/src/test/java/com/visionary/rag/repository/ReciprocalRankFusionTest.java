package com.visionary.rag.repository;

import com.visionary.rag.VectorDbService.KnowledgeFragment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReciprocalRankFusionTest {

    @Test
    void boostsDocumentsPresentInBothRankedLists() {
        KnowledgeFragment shared = fragment("shared-chunk", "卷积输出尺寸 padding stride 公式", 0.8D);
        KnowledgeFragment chromaOnly = fragment("chroma-only", "反向传播梯度链式法则", 0.75D);
        KnowledgeFragment bm25Only = fragment("bm25-only", "激活函数 ReLU 与 Sigmoid", 0.7D);

        List<KnowledgeFragment> fused = ReciprocalRankFusion.fuse(
                List.of(
                        List.of(shared, chromaOnly),
                        List.of(shared, bm25Only)
                ),
                2,
                60
        );

        assertEquals(2, fused.size());
        assertEquals("shared-chunk", fused.get(0).chunkId());
        assertTrue(fused.get(0).score() >= 0.25D);
    }

    @Test
    void returnsSingleListResultsWhenOtherListEmpty() {
        KnowledgeFragment only = fragment("doc-1", "CNN padding", 0.6D);
        List<KnowledgeFragment> fused = ReciprocalRankFusion.fuse(
                List.of(List.of(only), List.of()),
                1,
                60
        );
        assertEquals(1, fused.size());
        assertEquals("doc-1", fused.get(0).chunkId());
    }

    private static KnowledgeFragment fragment(String id, String content, double score) {
        return new KnowledgeFragment(
                content,
                "concept_layer",
                "rag-fallback/corpus.md",
                score,
                "text_chunk",
                "",
                "concept_layer",
                "concept_layer",
                id,
                id,
                "rag-fallback/corpus.md",
                0,
                0,
                content.length()
        );
    }
}
