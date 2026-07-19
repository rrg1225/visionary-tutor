package com.visionary.recommendation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.dto.ResourceRecommendationDto;
import com.visionary.entity.GeneratedArtifact;
import com.visionary.entity.LearningSession;
import com.visionary.rag.LexicalSimilarityScorer;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Hybrid recommendation: semantic recall (L1) + explainable rule reranking (L2).
 *
 * <h2>Phase 1 — Semantic Recall</h2>
 * <ol>
 *   <li>Chroma / vector index similarity (DashScope embeddings when configured)</li>
 *   <li>Live {@link EmbeddingModel} cosine similarity (same DashScope route in demo)</li>
 *   <li>Local TF-IDF lexical overlap ({@link LexicalSimilarityScorer})</li>
 * </ol>
 * Each step is wrapped in try/catch; failures degrade silently to the next tier.
 *
 * <h2>Phase 2 — Rule Rerank</h2>
 * <pre>
 *   finalScore = baseSemantic × (1 + weakBoost) × (1 + stageBoost) × (1 + freshnessBoost)
 *   weakBoost   = 0.20 when resource text hits learner weak-point terms
 *   stageBoost  = 0.10 when artifact type matches session learning phase
 *   freshness   ∈ [0, 0.05] linear by gmt_created rank within candidate set
 * </pre>
 * Display score = round(finalScore × 100), floor 40 when no semantic signal.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HybridRecommendationEngine {

    static final int SCORE_SCALE = 100;
    static final double FALLBACK_SEMANTIC_BASE = 0.40D;
    static final double WEAK_POINT_BOOST = 0.20D;
    static final double STAGE_BOOST = 0.10D;
    static final double MAX_FRESHNESS_BOOST = 0.05D;

    private static final Pattern SPLIT_WEAK = Pattern.compile("[,，、;；\\n]+");

    @Nullable
    private final EmbeddingModel embeddingModel;
    private final LexicalSimilarityScorer lexicalSimilarityScorer;
    private final ObjectMapper objectMapper;

    public ResourceRecommendationDto recommend(
            GeneratedArtifact artifact,
            RecommendContext context,
            Map<Long, Double> vectorScores
    ) {
        if (artifact == null) {
            return null;
        }

        RecallResult recall = semanticRecall(artifact, context, vectorScores);
        RerankResult rerank = ruleRerank(artifact, recall, context);

        String recommendationReason = String.join("；", rerank.reasons());
        if (recommendationReason.isBlank()) {
            recommendationReason = recall.defaultReason();
        }
        recommendationReason = appendRecallMode(recommendationReason, recall.mode());

        return new ResourceRecommendationDto(
                artifact.getId(),
                artifact.getArtifactType() != null ? artifact.getArtifactType().name() : "UNKNOWN",
                nullToEmpty(artifact.getTitle()),
                recommendationReason,
                rerank.finalScore()
        );
    }

    /**
     * Phase 1: vector → embedding → TF-IDF, never throws.
     */
    RecallResult semanticRecall(
            GeneratedArtifact artifact,
            RecommendContext context,
            Map<Long, Double> vectorScores
    ) {
        Long artifactId = artifact.getId();

        try {
            if (vectorScores != null && artifactId != null) {
                Double chromaScore = vectorScores.get(artifactId);
                if (chromaScore != null && chromaScore > 0.0D) {
                    return RecallResult.of(
                            chromaScore,
                            RecallMode.CHROMA_VECTOR,
                            "与您当前知识体系语义高度相关"
                    );
                }
            }
        } catch (Exception ex) {
            log.warn("Semantic recall (vector index) skipped: {}", ex.getMessage());
        }

        try {
            if (embeddingModel != null && context != null && !context.query().isBlank()) {
                double embeddingScore = embeddingSimilarity(context.query(), artifact);
                if (embeddingScore > 0.0D) {
                    return RecallResult.of(
                            embeddingScore,
                            RecallMode.DASHSCOPE_EMBEDDING,
                            "与您当前知识体系语义高度相关"
                    );
                }
            }
        } catch (Exception ex) {
            log.warn("Semantic recall (embedding API) degraded: {}", ex.getMessage());
        }

        try {
            String lexicalQuery = joinNonBlank(context != null ? context.query() : "", context != null ? context.weakPointsSnapshot() : "");
            double lexicalScore = lexicalSimilarityScorer.score(lexicalQuery, artifactText(artifact));
            if (lexicalScore > 0.0D) {
                return RecallResult.of(
                        lexicalScore,
                        RecallMode.LEXICAL_TFIDF,
                        "与您薄弱知识点关键词高度匹配"
                );
            }
        } catch (Exception ex) {
            log.warn("Semantic recall (lexical TF-IDF) failed: {}", ex.getMessage());
        }

        return RecallResult.fallback();
    }

    /**
     * Phase 2: multiplicative business boosts on semantic base.
     */
    RerankResult ruleRerank(GeneratedArtifact artifact, RecallResult recall, RecommendContext context) {
        double base = recall.semanticScore() > 0.0D ? recall.semanticScore() : FALLBACK_SEMANTIC_BASE;
        double multiplier = 1.0D;
        List<String> reasons = new ArrayList<>();

        if (recall.semanticScore() > 0.0D) {
            reasons.add(recall.defaultReason());
        }

        if (context != null && matchesWeakPoints(artifact, context.weakPointsSnapshot())) {
            multiplier *= (1.0D + WEAK_POINT_BOOST);
            reasons.add("针对您的弱点精准推送");
        }

        if (context != null && matchesLearningStage(artifact, context.learningPhase())) {
            multiplier *= (1.0D + STAGE_BOOST);
            reasons.add("符合您当前学习阶段");
        }

        double freshness = context != null ? freshnessBonus(artifact, context.candidateArtifacts()) : 0.0D;
        if (freshness > 0.0D) {
            multiplier *= (1.0D + freshness);
            reasons.add("最新生成资源优先展示");
        }

        double composite = base * multiplier;
        int finalScore = (int) Math.round(composite * SCORE_SCALE);
        if (finalScore < 40 && (recall.mode() == RecallMode.FALLBACK || recall.mode() == RecallMode.LEXICAL_TFIDF)) {
            finalScore = 40;
        }
        return new RerankResult(finalScore, reasons);
    }

    private double embeddingSimilarity(String query, GeneratedArtifact artifact) {
        if (embeddingModel == null || query == null || query.isBlank()) {
            return 0.0D;
        }
        String resourceText = artifactText(artifact);
        if (resourceText.isBlank()) {
            return 0.0D;
        }

        String truncatedQuery = query.length() > 2000 ? query.substring(0, 2000) : query;
        String truncatedResource = resourceText.length() > 2000 ? resourceText.substring(0, 2000) : resourceText;

        var queryEmbedding = embeddingModel.embed(TextSegment.from(truncatedQuery));
        var resourceEmbedding = embeddingModel.embed(TextSegment.from(truncatedResource));
        if (queryEmbedding == null || resourceEmbedding == null
                || queryEmbedding.content() == null || resourceEmbedding.content() == null) {
            return 0.0D;
        }
        return cosineSimilarity(queryEmbedding.content(), resourceEmbedding.content());
    }

    private static double cosineSimilarity(Embedding left, Embedding right) {
        if (left == null || right == null) {
            return 0.0D;
        }
        float[] a = left.vector();
        float[] b = right.vector();
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0.0D;
        }
        double dot = 0.0D;
        double normA = 0.0D;
        double normB = 0.0D;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA <= 0.0D || normB <= 0.0D) {
            return 0.0D;
        }
        double similarity = dot / (Math.sqrt(normA) * Math.sqrt(normB));
        return Math.max(0.0D, Math.min(1.0D, (similarity + 1.0D) / 2.0D));
    }

    private boolean matchesWeakPoints(GeneratedArtifact artifact, String weakPointsSnapshot) {
        List<String> terms = extractWeakPointTerms(weakPointsSnapshot);
        if (terms.isEmpty()) {
            return false;
        }
        String haystack = artifactText(artifact).toLowerCase(Locale.ROOT);
        for (String term : terms) {
            if (term.length() >= 2 && haystack.contains(term.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private List<String> extractWeakPointTerms(String weakPointsSnapshot) {
        List<String> terms = new ArrayList<>();
        if (weakPointsSnapshot == null || weakPointsSnapshot.isBlank()) {
            return terms;
        }
        try {
            JsonNode root = objectMapper.readTree(weakPointsSnapshot);
            if (root.isArray()) {
                root.forEach(node -> addWeakTerm(terms, node.asText("")));
                return terms;
            }
            JsonNode valueNode = root.path("weakPoints").path("value");
            if (valueNode.isArray()) {
                valueNode.forEach(node -> addWeakTerm(terms, node.asText("")));
                return terms;
            }
            if (valueNode.isTextual()) {
                addSplitTerms(terms, valueNode.asText());
                return terms;
            }
        } catch (Exception ignored) {
            // Plain text / CSV weak-point snapshot
        }
        addSplitTerms(terms, weakPointsSnapshot);
        return terms;
    }

    private static void addSplitTerms(List<String> terms, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        for (String part : SPLIT_WEAK.split(raw)) {
            addWeakTerm(terms, part);
        }
    }

    private static void addWeakTerm(List<String> terms, String term) {
        if (term == null) {
            return;
        }
        String trimmed = term.trim();
        if (trimmed.length() >= 2) {
            terms.add(trimmed);
        }
    }

    private static boolean matchesLearningStage(
            GeneratedArtifact artifact,
            LearningSession.LearningPhase phase
    ) {
        if (phase == null || artifact.getArtifactType() == null) {
            return false;
        }
        GeneratedArtifact.ArtifactType type = artifact.getArtifactType();
        return switch (phase) {
            case KNOWLEDGE_DIAGNOSIS -> type == GeneratedArtifact.ArtifactType.QUIZ;
            case RESOURCE_GENERATION -> type == GeneratedArtifact.ArtifactType.HANDOUT
                    || type == GeneratedArtifact.ArtifactType.MINDMAP
                    || type == GeneratedArtifact.ArtifactType.VISUALIZATION
                    || type == GeneratedArtifact.ArtifactType.EXTENDED_READING;
            case ASSESSMENT_FEEDBACK -> type == GeneratedArtifact.ArtifactType.QUIZ
                    || type == GeneratedArtifact.ArtifactType.CODE_PRACTICE;
            case STUDENT_PROFILE -> type == GeneratedArtifact.ArtifactType.LEARNING_PATH;
        };
    }

    static double freshnessBonus(GeneratedArtifact artifact, List<GeneratedArtifact> candidates) {
        if (artifact == null || artifact.getGmtCreated() == null || candidates == null || candidates.isEmpty()) {
            return 0.0D;
        }
        LocalDateTime oldest = candidates.stream()
                .map(GeneratedArtifact::getGmtCreated)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
        LocalDateTime newest = candidates.stream()
                .map(GeneratedArtifact::getGmtCreated)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        if (oldest == null || newest == null) {
            return 0.0D;
        }
        if (!newest.isAfter(oldest)) {
            return MAX_FRESHNESS_BOOST;
        }
        long spanSeconds = Math.max(1L, ChronoUnit.SECONDS.between(oldest, newest));
        long offsetSeconds = Math.max(0L, ChronoUnit.SECONDS.between(oldest, artifact.getGmtCreated()));
        return MAX_FRESHNESS_BOOST * (offsetSeconds / (double) spanSeconds);
    }

    private static String artifactText(GeneratedArtifact artifact) {
        return joinNonBlank(
                artifact.getTitle(),
                artifact.getContentMarkdown(),
                artifact.getArtifactType() != null ? artifact.getArtifactType().name() : ""
        );
    }

    private static String joinNonBlank(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append(part.trim());
            }
        }
        return sb.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String appendRecallMode(String reason, RecallMode mode) {
        if (mode == null || mode == RecallMode.FALLBACK) {
            return reason;
        }
        String suffix = switch (mode) {
            case CHROMA_VECTOR -> " · Chroma向量检索";
            case DASHSCOPE_EMBEDDING -> " · Embedding语义匹配";
            case LEXICAL_TFIDF -> " · 关键词TF-IDF";
            default -> "";
        };
        if (suffix.isBlank() || reason.contains("向量") || reason.contains("Embedding")) {
            return reason;
        }
        return reason + suffix;
    }

    public record RecommendContext(
            String query,
            String weakPointsSnapshot,
            String cognitiveStyle,
            boolean recentQuizLow,
            LearningSession.LearningPhase learningPhase,
            List<GeneratedArtifact> candidateArtifacts
    ) {
    }

    record RecallResult(double semanticScore, RecallMode mode, String defaultReason) {
        static RecallResult of(double score, RecallMode mode, String reason) {
            return new RecallResult(score, mode, reason);
        }

        static RecallResult fallback() {
            return new RecallResult(0.0D, RecallMode.FALLBACK, "规则兜底推荐");
        }
    }

    enum RecallMode {
        CHROMA_VECTOR,
        DASHSCOPE_EMBEDDING,
        LEXICAL_TFIDF,
        FALLBACK
    }

    record RerankResult(int finalScore, List<String> reasons) {
    }
}
