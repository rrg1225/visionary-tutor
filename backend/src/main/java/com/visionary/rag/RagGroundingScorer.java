package com.visionary.rag;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared lexical overlap scorer for citation / RAG faithfulness checks.
 */
@Component
public class RagGroundingScorer {

    private static final Pattern CITATION_PATTERN = Pattern.compile("\\bcite-[\\p{IsHan}A-Za-z0-9_.:-]+\\b");
    private static final Pattern LATIN_TERM_PATTERN = Pattern.compile("[a-z0-9_+\\-*/=]{2,}");
    private static final Pattern HAN_RUN = Pattern.compile("[\\u4e00-\\u9fff]{2,}");

    private final EmbeddingModel embeddingModel;

    public RagGroundingScorer(@Autowired(required = false) EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public double overlapScore(String claim, String evidence) {
        Set<String> claimTerms = terms(claim);
        if (claimTerms.isEmpty()) {
            return 0D;
        }
        Set<String> evidenceTerms = terms(evidence);
        int hit = 0;
        for (String term : claimTerms) {
            if (evidenceTerms.contains(term)) {
                hit++;
            }
        }
        return (double) hit / (double) claimTerms.size();
    }

    /**
     * Score how well {@code answer} is supported by retrieved citation excerpts.
     */
    public double faithfulnessAgainstCitations(String answer, RagRetrievalResult rag) {
        if (answer == null || answer.isBlank() || rag == null || rag.citations() == null || rag.citations().isEmpty()) {
            return 0D;
        }
        String cleaned = CITATION_PATTERN.matcher(answer).replaceAll(" ").trim();
        if (cleaned.isBlank()) {
            return 0D;
        }
        double best = 0D;
        for (RagCitation citation : rag.citations()) {
            if (citation.excerpt() == null || citation.excerpt().isBlank()) {
                continue;
            }
            best = Math.max(best, overlapScore(cleaned, citation.excerpt()));
        }
        return best;
    }

    /**
     * Measures whether every citation anchor embedded in a diagnostic context belongs to
     * the citations returned by the same retrieval. Unlike answer faithfulness, this is
     * intended for context diagnostics where the payload is a concatenation of fragments.
     */
    public double citationAnchorCoverage(String context, RagRetrievalResult rag) {
        if (context == null || context.isBlank() || rag == null
                || rag.citations() == null || rag.citations().isEmpty()) {
            return 0D;
        }
        Set<String> validIds = new LinkedHashSet<>();
        for (RagCitation citation : rag.citations()) {
            if (citation.citationId() != null && !citation.citationId().isBlank()) {
                validIds.add(citation.citationId().toLowerCase(Locale.ROOT));
            }
        }
        Set<String> usedIds = new LinkedHashSet<>();
        Matcher matcher = CITATION_PATTERN.matcher(context.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            usedIds.add(matcher.group());
        }
        if (usedIds.isEmpty()) {
            return 0D;
        }
        long supported = usedIds.stream().filter(validIds::contains).count();
        return (double) supported / (double) usedIds.size();
    }

    /**
     * Embedding cosine similarity against best matching citation excerpt.
     */
    public double semanticFaithfulnessAgainstCitations(String answer, RagRetrievalResult rag) {
        if (embeddingModel == null || answer == null || answer.isBlank()
                || rag == null || rag.citations() == null || rag.citations().isEmpty()) {
            return 0D;
        }
        String cleaned = CITATION_PATTERN.matcher(answer).replaceAll(" ").trim();
        if (cleaned.isBlank()) {
            return 0D;
        }
        String sample = cleaned.length() > 512 ? cleaned.substring(0, 512) : cleaned;
        double best = 0D;
        for (RagCitation citation : rag.citations()) {
            if (citation.excerpt() == null || citation.excerpt().isBlank()) {
                continue;
            }
            best = Math.max(best, semanticSimilarity(sample, citation.excerpt()));
        }
        return best;
    }

    public double semanticSimilarity(String left, String right) {
        if (embeddingModel == null || left == null || right == null || left.isBlank() || right.isBlank()) {
            return 0D;
        }
        try {
            Embedding leftEmbedding = embeddingModel.embed(left).content();
            Embedding rightEmbedding = embeddingModel.embed(right).content();
            return cosineSimilarity(leftEmbedding.vector(), rightEmbedding.vector());
        } catch (Exception e) {
            return 0D;
        }
    }

    private static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0D;
        }
        double dot = 0D;
        double normA = 0D;
        double normB = 0D;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0D || normB == 0D) {
            return 0D;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    public Set<String> terms(String text) {
        Set<String> result = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        String normalized = text.toLowerCase(Locale.ROOT)
                .replaceAll("\\bcite-[\\p{IsHan}A-Za-z0-9_.:-]+\\b", " ")
                .replaceAll("[\\p{Punct}，。！？；：“”‘’（）【】《》、]", " ");

        Matcher latin = LATIN_TERM_PATTERN.matcher(normalized);
        while (latin.find()) {
            result.add(latin.group());
        }

        Matcher hanRuns = HAN_RUN.matcher(normalized);
        while (hanRuns.find()) {
            String run = hanRuns.group();
            if (run.length() <= 4) {
                result.add(run);
            } else {
                for (int i = 0; i < run.length() - 1; i++) {
                    result.add(run.substring(i, i + 2));
                }
            }
        }
        return result;
    }
}
