package com.visionary.rag.repository;

import com.visionary.rag.VectorDbService.KnowledgeFragment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-memory Okapi BM25 index for HA fallback retrieval.
 */
final class Bm25Index {

    private static final double K1 = 1.2D;
    private static final double B = 0.75D;
    private static final Pattern LATIN_TERM = Pattern.compile("[a-z0-9_+\\-*/=]{2,}");
    private static final Pattern HAN_RUN = Pattern.compile("[\\u4e00-\\u9fff]{2,}");

    private final List<IndexedDoc> documents = new ArrayList<>();
    private final Map<String, Integer> documentFrequency = new HashMap<>();
    private double avgDocLength;

    void addDocument(String docId, String content, String source, String layer, String category) {
        if (content == null || content.isBlank()) {
            return;
        }
        Set<String> terms = tokenize(content + " " + source + " " + category);
        if (terms.isEmpty()) {
            return;
        }
        Map<String, Integer> termFrequency = new HashMap<>();
        for (String term : terms) {
            termFrequency.merge(term, 1, Integer::sum);
        }
        documents.add(new IndexedDoc(
                docId,
                content,
                source,
                layer,
                category,
                termFrequency,
                termFrequency.values().stream().mapToInt(Integer::intValue).sum()
        ));
        for (String term : terms) {
            documentFrequency.merge(term, 1, Integer::sum);
        }
    }

    void finalizeIndex() {
        if (documents.isEmpty()) {
            avgDocLength = 0D;
            return;
        }
        avgDocLength = documents.stream().mapToInt(IndexedDoc::length).average().orElse(0D);
    }

    int size() {
        return documents.size();
    }

    List<KnowledgeFragment> search(String query, int topK, Set<String> allowedLayers) {
        if (documents.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }
        Set<String> queryTerms = tokenize(query);
        if (queryTerms.isEmpty()) {
            return List.of();
        }

        int docCount = documents.size();
        List<ScoredDoc> scored = new ArrayList<>();
        for (IndexedDoc doc : documents) {
            if (!matchesLayer(doc.layer(), allowedLayers)) {
                continue;
            }
            double score = scoreDocument(doc, queryTerms, docCount);
            if (score > 0D) {
                scored.add(new ScoredDoc(doc, score));
            }
        }
        if (scored.isEmpty()) {
            return List.of();
        }

        scored.sort(Comparator.comparingDouble(ScoredDoc::score).reversed());
        double maxScore = scored.get(0).score();
        int limit = Math.max(1, topK);
        List<KnowledgeFragment> results = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, scored.size()); i++) {
            ScoredDoc hit = scored.get(i);
            double normalized = maxScore <= 0D ? 0.25D : Math.max(0.25D, Math.min(0.99D, hit.score() / maxScore));
            IndexedDoc doc = hit.doc();
            results.add(new KnowledgeFragment(
                    doc.content(),
                    doc.category(),
                    doc.source(),
                    normalized,
                    "text_chunk",
                    "",
                    doc.layer(),
                    doc.layer(),
                    doc.docId(),
                    doc.docId(),
                    doc.source(),
                    0,
                    0,
                    doc.content().length()
            ));
        }
        return results;
    }

    private double scoreDocument(IndexedDoc doc, Set<String> queryTerms, int docCount) {
        double score = 0D;
        for (String term : queryTerms) {
            int tf = doc.termFrequency().getOrDefault(term, 0);
            if (tf == 0) {
                continue;
            }
            int df = documentFrequency.getOrDefault(term, 0);
            double idf = Math.log(1D + (docCount - df + 0.5D) / (df + 0.5D));
            double numerator = tf * (K1 + 1D);
            double denominator = tf + K1 * (1D - B + B * doc.length() / Math.max(1D, avgDocLength));
            score += idf * (numerator / denominator);
        }
        return score;
    }

    private static boolean matchesLayer(String layer, Set<String> allowedLayers) {
        if (allowedLayers == null || allowedLayers.isEmpty()) {
            return true;
        }
        if (layer == null || layer.isBlank()) {
            return false;
        }
        return allowedLayers.contains(layer);
    }

    private static Set<String> tokenize(String text) {
        Set<String> terms = new HashSet<>();
        if (text == null || text.isBlank()) {
            return terms;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        Matcher latin = LATIN_TERM.matcher(normalized);
        while (latin.find()) {
            terms.add(latin.group());
        }
        Matcher han = HAN_RUN.matcher(text);
        while (han.find()) {
            String run = han.group();
            terms.add(run);
            if (run.length() >= 2) {
                for (int i = 0; i < run.length() - 1; i++) {
                    terms.add(run.substring(i, i + 2));
                }
            }
        }
        return terms;
    }

    private record IndexedDoc(
            String docId,
            String content,
            String source,
            String layer,
            String category,
            Map<String, Integer> termFrequency,
            int length
    ) {
    }

    private record ScoredDoc(IndexedDoc doc, double score) {
    }
}
