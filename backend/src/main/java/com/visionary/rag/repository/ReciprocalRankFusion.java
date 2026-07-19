package com.visionary.rag.repository;

import com.visionary.rag.VectorDbService.KnowledgeFragment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion (RRF) for merging ranked lists from vector and lexical retrievers.
 * score(d) = sum_i 1 / (k + rank_i(d))
 */
public final class ReciprocalRankFusion {

    public static final int DEFAULT_K = 60;

    private ReciprocalRankFusion() {
    }

    public static List<KnowledgeFragment> fuse(List<List<KnowledgeFragment>> rankedLists, int topK) {
        return fuse(rankedLists, topK, DEFAULT_K);
    }

    public static List<KnowledgeFragment> fuse(List<List<KnowledgeFragment>> rankedLists, int topK, int rrfK) {
        if (rankedLists == null || rankedLists.isEmpty() || topK <= 0) {
            return List.of();
        }
        int k = Math.max(1, rrfK);
        Map<String, Double> fusedScores = new HashMap<>();
        Map<String, KnowledgeFragment> fragmentsByKey = new LinkedHashMap<>();

        for (List<KnowledgeFragment> rankedList : rankedLists) {
            if (rankedList == null || rankedList.isEmpty()) {
                continue;
            }
            for (int rank = 0; rank < rankedList.size(); rank++) {
                KnowledgeFragment fragment = rankedList.get(rank);
                if (fragment == null) {
                    continue;
                }
                String key = dedupKey(fragment);
                fusedScores.merge(key, 1.0D / (k + rank + 1), Double::sum);
                fragmentsByKey.putIfAbsent(key, fragment);
            }
        }

        if (fusedScores.isEmpty()) {
            return List.of();
        }

        double maxScore = fusedScores.values().stream().max(Double::compare).orElse(0D);
        List<Map.Entry<String, Double>> ordered = new ArrayList<>(fusedScores.entrySet());
        ordered.sort(Comparator.comparingDouble(Map.Entry<String, Double>::getValue).reversed());

        int limit = Math.max(1, topK);
        List<KnowledgeFragment> results = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, ordered.size()); i++) {
            Map.Entry<String, Double> entry = ordered.get(i);
            KnowledgeFragment source = fragmentsByKey.get(entry.getKey());
            if (source == null) {
                continue;
            }
            results.add(withFusedScore(source, entry.getValue(), maxScore));
        }
        return List.copyOf(results);
    }

    static String dedupKey(KnowledgeFragment fragment) {
        String chunkId = firstNonBlank(fragment.chunkId(), fragment.vectorId(), "");
        if (!chunkId.isBlank()) {
            return chunkId;
        }
        String sourcePath = firstNonBlank(fragment.sourcePath(), fragment.source(), "unknown");
        Integer chunkIndex = fragment.chunkIndex();
        return sourcePath + "#" + (chunkIndex == null ? 0 : chunkIndex);
    }

    private static KnowledgeFragment withFusedScore(KnowledgeFragment source, double rawScore, double maxScore) {
        double normalized = maxScore <= 0D
                ? 0.25D
                : Math.max(0.25D, Math.min(0.99D, rawScore / maxScore));
        return new KnowledgeFragment(
                source.content(),
                source.category(),
                source.source(),
                normalized,
                source.chunkType(),
                source.imagePath(),
                source.layer(),
                source.chromaLayer(),
                source.vectorId(),
                source.chunkId(),
                source.sourcePath(),
                source.chunkIndex(),
                source.chunkStart(),
                source.chunkEnd()
        );
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
