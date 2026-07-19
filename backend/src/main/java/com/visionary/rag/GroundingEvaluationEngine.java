package com.visionary.rag;

import com.visionary.dto.GroundingMetrics;
import com.visionary.dto.RagContextRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Side-path grounding evaluator: transparent proxy metrics over RAG outputs.
 * <p>
 * Does not alter retrieval; scores are derived from citation coverage and
 * lexical overlap only (no external LLM judges).
 * </p>
 *
 * <h2>Proxy 1 — Citation Ratio</h2>
 * <pre>
 *   citedBlocks = count(chunks where overlap(response, chunk) ≥ τ_cite)
 *   citationRatio = citedBlocks / |retrievedChunks|   (0 if no chunks)
 * </pre>
 * A chunk counts as "cited" when the answer shares enough keyword/bigram overlap
 * with that chunk ({@code τ_cite = 0.08}), or when a {@code cite-*} marker in the
 * answer aligns with terms present in the chunk.
 *
 * <h2>Proxy 2 — Overlap Score (faithfulness proxy)</h2>
 * <pre>
 *   overlapScore = max_i overlap(response, chunk_i)
 * </pre>
 * Uses the same token/bigram lexicon as {@link RagGroundingScorer#overlapScore}.
 * The maximum across chunks reflects best-supported evidence (conservative proxy
 * for answer–corpus alignment).
 */
@Component
@RequiredArgsConstructor
public class GroundingEvaluationEngine {

    private static final Pattern CITATION_PATTERN = Pattern.compile("\\bcite-[\\p{IsHan}A-Za-z0-9_.:-]+\\b");
    private static final double CHUNK_CITED_OVERLAP = 0.08D;

    private final RagGroundingScorer groundingScorer;

    /**
     * Evaluates grounding proxies for an LLM answer against retrieved text chunks.
     *
     * @param llmResponse      generated answer (may contain {@code cite-*} markers)
     * @param retrievedChunks  excerpt texts returned by RAG (empty → zero scores)
     * @return metrics with embedded {@link RagContextRecord} and risk flag helper
     */
    public GroundingMetrics evaluate(String llmResponse, List<String> retrievedChunks) {
        String response = llmResponse == null ? "" : llmResponse;
        List<String> chunks = sanitizeChunks(retrievedChunks);
        int responseLength = response.length();

        int citedBlocks = countCitedBlocks(response, chunks);
        int retrievedDocs = chunks.size();

        double citationRatio = retrievedDocs == 0 ? 0D : (double) citedBlocks / retrievedDocs;
        double overlapScore = computeOverlapScore(response, chunks);

        RagContextRecord context = new RagContextRecord(retrievedDocs, citedBlocks, responseLength);
        return new GroundingMetrics(
                round(citationRatio),
                round(overlapScore),
                context
        );
    }

    private static List<String> sanitizeChunks(List<String> retrievedChunks) {
        if (retrievedChunks == null || retrievedChunks.isEmpty()) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        for (String chunk : retrievedChunks) {
            if (chunk != null && !chunk.isBlank()) {
                chunks.add(chunk.trim());
            }
        }
        return chunks;
    }

    private int countCitedBlocks(String response, List<String> chunks) {
        if (chunks.isEmpty() || response.isBlank()) {
            return 0;
        }
        String cleaned = CITATION_PATTERN.matcher(response).replaceAll(" ").trim();
        int cited = 0;
        for (String chunk : chunks) {
            if (isChunkCited(cleaned, chunk)) {
                cited++;
            }
        }
        return cited;
    }

    private boolean isChunkCited(String cleanedResponse, String chunk) {
        if (groundingScorer.overlapScore(cleanedResponse, chunk) >= CHUNK_CITED_OVERLAP) {
            return true;
        }
        var chunkTerms = groundingScorer.terms(chunk);
        if (chunkTerms.isEmpty()) {
            return false;
        }
        String lower = cleanedResponse.toLowerCase(Locale.ROOT);
        int hits = 0;
        for (String term : chunkTerms) {
            if (lower.contains(term.toLowerCase(Locale.ROOT))) {
                hits++;
            }
            if (hits >= 2) {
                return true;
            }
        }
        return false;
    }

    private double computeOverlapScore(String response, List<String> chunks) {
        if (response.isBlank() || chunks.isEmpty()) {
            return 0D;
        }
        String cleaned = CITATION_PATTERN.matcher(response).replaceAll(" ").trim();
        if (cleaned.isBlank()) {
            return 0D;
        }
        double best = 0D;
        for (String chunk : chunks) {
            best = Math.max(best, groundingScorer.overlapScore(cleaned, chunk));
        }
        return best;
    }

    private static double round(double value) {
        return Math.round(value * 1000D) / 1000D;
    }
}
