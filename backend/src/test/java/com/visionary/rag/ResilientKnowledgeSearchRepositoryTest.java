package com.visionary.rag;

import com.visionary.config.VectorDbConfig;
import com.visionary.rag.VectorDbService.KnowledgeFragment;
import com.visionary.rag.repository.ChromaKnowledgeSearchRepository;
import com.visionary.rag.repository.InMemoryBm25KnowledgeRepository;
import com.visionary.rag.repository.KnowledgeSearchOutcome;
import com.visionary.rag.repository.ResilientKnowledgeSearchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResilientKnowledgeSearchRepositoryTest {

    @Mock
    private ChromaKnowledgeSearchRepository chromaRepository;

    @Mock
    private InMemoryBm25KnowledgeRepository bm25Repository;

    private VectorDbConfig config;
    private ResilientKnowledgeSearchRepository repository;

    @BeforeEach
    void setUp() {
        config = new VectorDbConfig();
        config.setBm25FallbackEnabled(true);
        config.setHybridRetrievalEnabled(false);
        config.setChromaQueryTimeoutMs(50L);
        config.setBm25FallbackSlaMs(50L);
        repository = new ResilientKnowledgeSearchRepository(chromaRepository, bm25Repository, config);
    }

    @Test
    void fusesChromaAndBm25WithHybridRrf() {
        config.setHybridRetrievalEnabled(true);
        when(chromaRepository.isReady()).thenReturn(true);
        when(bm25Repository.isReady()).thenReturn(true);

        KnowledgeFragment shared = sampleFragment("shared-chunk", "卷积输出尺寸 padding stride 公式");
        KnowledgeFragment chromaOnly = sampleFragment("chroma-only", "反向传播梯度链式法则");
        KnowledgeFragment bm25Only = sampleFragment("bm25-only", "激活函数 ReLU");

        when(chromaRepository.searchWithTimeout(any(), anyInt(), any(), anyLong()))
                .thenReturn(List.of(shared, chromaOnly));
        when(bm25Repository.search(eq("卷积输出尺寸 padding stride"), eq(8), any()))
                .thenReturn(List.of(shared, bm25Only));

        KnowledgeSearchOutcome outcome = repository.search("卷积输出尺寸 padding stride", 4, Set.of("concept_layer"));

        assertEquals(KnowledgeSearchOutcome.MODE_HYBRID, outcome.retrievalMode());
        assertTrue(!outcome.highAvailabilityFallback());
        assertEquals("shared-chunk", outcome.fragments().get(0).chunkId());
        assertEquals(3, outcome.fragments().size());
    }

    @Test
    void fallsBackToBm25WhenChromaTimesOut() {
        when(chromaRepository.isReady()).thenReturn(true);
        when(chromaRepository.searchWithTimeout(any(), anyInt(), any(), anyLong()))
                .thenThrow(new KnowledgeRetrievalException("timeout", "cnn", "visionary_global_knowledge", null));
        when(bm25Repository.isReady()).thenReturn(true);
        when(bm25Repository.search(eq("cnn padding stride"), eq(4), any()))
                .thenReturn(List.of(sampleFragment("bm25-1", "CNN padding stride 公式")));

        KnowledgeSearchOutcome outcome = repository.search("cnn padding stride", 4, Set.of("concept_layer"));

        assertTrue(outcome.highAvailabilityFallback());
        assertEquals(KnowledgeSearchOutcome.MODE_BM25, outcome.retrievalMode());
        assertEquals(1, outcome.fragments().size());
    }

    @Test
    void usesBm25DirectlyWhenChromaUnavailable() {
        when(chromaRepository.isReady()).thenReturn(false);
        when(bm25Repository.isReady()).thenReturn(true);
        when(bm25Repository.search(eq("卷积输出尺寸"), eq(3), any()))
                .thenReturn(List.of(sampleFragment("bm25-2", "卷积输出尺寸计算公式")));

        KnowledgeSearchOutcome outcome = repository.search("卷积输出尺寸", 3, Set.of("math_layer"));

        assertTrue(outcome.highAvailabilityFallback());
        assertEquals(1, outcome.fragments().size());
    }

    private static VectorDbService.KnowledgeFragment sampleFragment(String id, String content) {
        return new VectorDbService.KnowledgeFragment(
                content,
                "concept_layer",
                "rag-fallback/corpus.md",
                0.82D,
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
