package com.visionary.rag.repository;

import com.visionary.rag.VectorDbService.KnowledgeFragment;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates that hybrid RRF improves recall for long, keyword-rich Chinese queries.
 */
class HybridRetrievalAccuracyTest {

    private static final String LONG_QUERY =
            "当卷积神经网络采用三乘三卷积核、步长 stride 等于一、并在输入边缘进行 padding 补零时，"
                    + "输出特征图尺寸与输入尺寸之间的关系是什么";

    @Test
    void longComplexQueryRanksSharedEvidenceFirstAfterRrfFusion() {
        KnowledgeFragment longFormEvidence = fragment(
                "long-form-conv-size",
                "当卷积神经网络采用三乘三卷积核、步长 stride 等于一、并在输入边缘进行 padding 补零时，"
                        + "输出特征图尺寸与输入尺寸之间的关系可由公式"
                        + "输出尺寸 = (输入尺寸 - 卷积核尺寸 + 2 × padding) / stride + 1 精确描述",
                0.55D
        );
        KnowledgeFragment shortVectorHit = fragment(
                "short-vector-hit",
                "深度学习优化器 Adam 与学习率调度",
                0.82D
        );
        KnowledgeFragment bm25KeywordHit = fragment(
                "long-form-conv-size",
                longFormEvidence.content(),
                0.91D
        );

        List<KnowledgeFragment> fused = ReciprocalRankFusion.fuse(
                List.of(
                        List.of(shortVectorHit, longFormEvidence),
                        List.of(bm25KeywordHit)
                ),
                2,
                60
        );

        assertEquals("long-form-conv-size", fused.get(0).chunkId());
        assertTrue(fused.get(0).content().contains("输出尺寸"));
        assertTrue(fused.get(0).score() >= 0.25D);
    }

    @Test
    void bm25IndexFindsLongSentenceKeywords() {
        Bm25Index index = new Bm25Index();
        index.addDocument(
                "long-form-conv-size",
                "当卷积神经网络采用三乘三卷积核、步长 stride 等于一、并在输入边缘进行 padding 补零时，"
                        + "输出特征图尺寸与输入尺寸之间的关系可由公式描述",
                "rag-fallback/corpus.md",
                "concept_layer",
                "concept_layer"
        );
        index.addDocument(
                "noise-doc",
                "循环神经网络 LSTM 门控机制与序列建模",
                "noise.md",
                "concept_layer",
                "concept_layer"
        );
        index.finalizeIndex();

        List<KnowledgeFragment> hits = index.search(LONG_QUERY, 1, Set.of("concept_layer"));

        assertEquals(1, hits.size());
        assertEquals("long-form-conv-size", hits.get(0).chunkId());
        assertTrue(hits.get(0).content().contains("padding"));
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
