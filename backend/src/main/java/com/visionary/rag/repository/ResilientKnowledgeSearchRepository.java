package com.visionary.rag.repository;

import com.visionary.config.VectorDbConfig;
import com.visionary.rag.VectorDbService.KnowledgeFragment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Chroma vector + in-memory BM25 hybrid retrieval with RRF fusion.
 * Falls back to BM25-only HA mode when Chroma is unavailable or times out.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ResilientKnowledgeSearchRepository {

    private final ChromaKnowledgeSearchRepository chromaRepository;
    private final InMemoryBm25KnowledgeRepository bm25Repository;
    private final VectorDbConfig config;

    public boolean isAvailable() {
        return chromaRepository.isReady() || bm25Repository.isReady();
    }

    public boolean isChromaAvailable() {
        return chromaRepository.isReady();
    }

    public boolean isBm25Available() {
        return bm25Repository.isReady();
    }

    public KnowledgeSearchOutcome search(String query, int topK, Set<String> allowedLayers) {
        if (query == null || query.isBlank()) {
            return KnowledgeSearchOutcome.empty();
        }

        if (shouldUseHybridSearch()) {
            return executeHybridSearch(query, topK, allowedLayers);
        }

        if (!config.isBm25FallbackEnabled()) {
            return executeChromaOnly(query, topK, allowedLayers);
        }

        if (chromaRepository.isReady()) {
            try {
                List<KnowledgeFragment> fragments = chromaRepository.searchWithTimeout(
                        query,
                        topK,
                        allowedLayers,
                        config.getChromaQueryTimeoutMs()
                );
                return KnowledgeSearchOutcome.chroma(fragments);
            } catch (RuntimeException e) {
                log.warn("Chroma search failed, switching to BM25 HA mode: {}", e.getMessage());
            }
        }

        return executeBm25Fallback(query, topK, allowedLayers);
    }

    private boolean shouldUseHybridSearch() {
        return config.isHybridRetrievalEnabled()
                && config.isBm25FallbackEnabled()
                && chromaRepository.isReady()
                && bm25Repository.isReady();
    }

    private KnowledgeSearchOutcome executeHybridSearch(String query, int topK, Set<String> allowedLayers) {
        int candidateK = Math.max(topK, topK * Math.max(1, config.getHybridCandidateMultiplier()));
        List<KnowledgeFragment> chromaResults = List.of();
        boolean chromaFailed = false;

        try {
            chromaResults = chromaRepository.searchWithTimeout(
                    query,
                    candidateK,
                    allowedLayers,
                    config.getChromaQueryTimeoutMs()
            );
            if (chromaResults == null) {
                chromaResults = List.of();
            }
        } catch (RuntimeException e) {
            chromaFailed = true;
            log.warn("Chroma path failed during hybrid search, degrading to BM25 HA: {}", e.getMessage());
        }

        if (chromaFailed) {
            return executeBm25Fallback(query, topK, allowedLayers);
        }

        try {
            List<KnowledgeFragment> bm25Results = bm25Repository.search(query, candidateK, allowedLayers);
            if (bm25Results == null) {
                bm25Results = List.of();
            }

            if (chromaResults.isEmpty() && bm25Results.isEmpty()) {
                return KnowledgeSearchOutcome.empty();
            }
            if (bm25Results.isEmpty()) {
                return KnowledgeSearchOutcome.chroma(limit(chromaResults, topK));
            }
            if (chromaResults.isEmpty()) {
                return KnowledgeSearchOutcome.bm25Ha(limit(bm25Results, topK));
            }

            List<KnowledgeFragment> fused = ReciprocalRankFusion.fuse(
                    List.of(chromaResults, bm25Results),
                    topK,
                    config.getRrfK()
            );
            if (fused.isEmpty()) {
                return KnowledgeSearchOutcome.chroma(limit(chromaResults, topK));
            }
            log.debug("Hybrid RRF fusion: chromaCandidates={}, bm25Candidates={}, fused={}",
                    chromaResults.size(), bm25Results.size(), fused.size());
            return KnowledgeSearchOutcome.hybrid(fused);
        } catch (Exception e) {
            log.warn("BM25 path failed during hybrid search, using Chroma only: {}", e.getMessage());
            return KnowledgeSearchOutcome.chroma(limit(chromaResults, topK));
        }
    }

    private KnowledgeSearchOutcome executeChromaOnly(String query, int topK, Set<String> allowedLayers) {
        if (!chromaRepository.isReady()) {
            return KnowledgeSearchOutcome.empty();
        }
        try {
            List<KnowledgeFragment> fragments = chromaRepository.searchWithTimeout(
                    query,
                    topK,
                    allowedLayers,
                    config.getChromaQueryTimeoutMs()
            );
            return KnowledgeSearchOutcome.chroma(fragments);
        } catch (Exception e) {
            log.warn("Chroma-only search failed without BM25 fallback enabled: {}", e.getMessage());
            return KnowledgeSearchOutcome.empty();
        }
    }

    private KnowledgeSearchOutcome executeBm25Fallback(String query, int topK, Set<String> allowedLayers) {
        if (!bm25Repository.isReady()) {
            return KnowledgeSearchOutcome.empty();
        }
        long started = System.nanoTime();
        try {
            List<KnowledgeFragment> fragments = bm25Repository.search(query, topK, allowedLayers);
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            if (elapsedMs > config.getBm25FallbackSlaMs()) {
                log.warn("BM25 HA fallback exceeded SLA: {}ms (limit={}ms)", elapsedMs, config.getBm25FallbackSlaMs());
            } else {
                log.info("BM25 HA fallback completed in {}ms with {} fragments", elapsedMs,
                        fragments == null ? 0 : fragments.size());
            }
            return KnowledgeSearchOutcome.bm25Ha(fragments == null ? Collections.emptyList() : fragments);
        } catch (Exception e) {
            log.error("BM25 HA fallback failed: {}", e.getMessage());
            return KnowledgeSearchOutcome.empty();
        }
    }

    private static List<KnowledgeFragment> limit(List<KnowledgeFragment> fragments, int topK) {
        if (fragments == null || fragments.isEmpty()) {
            return List.of();
        }
        return fragments.stream().limit(Math.max(1, topK)).collect(Collectors.toList());
    }
}
