package com.visionary.service;

import com.visionary.dto.ResourceRecommendationDto;
import com.visionary.dto.ResourceRecommendationResponse;
import com.visionary.entity.GeneratedArtifact;
import com.visionary.entity.LearningSession;
import com.visionary.entity.ResourceRecommendationLog;
import com.visionary.recommendation.HybridRecommendationEngine;
import com.visionary.recommendation.HybridRecommendationEngine.RecommendContext;
import com.visionary.repository.LearningSessionRepository;
import com.visionary.repository.ResourceRecommendationLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ResourceRecommendationService {

    private static final int TOP_N = 5;

    private final HybridRecommendationEngine hybridRecommendationEngine;
    private final ResourceVectorIndexService resourceVectorIndexService;
    @Nullable
    private final KnowledgeTracingService knowledgeTracingService;
    @Nullable
    private final ResourceRecommendationLogRepository recommendationLogRepository;
    @Nullable
    private final LearningSessionRepository learningSessionRepository;

    @Autowired
    public ResourceRecommendationService(
            HybridRecommendationEngine hybridRecommendationEngine,
            ResourceVectorIndexService resourceVectorIndexService,
            @Nullable KnowledgeTracingService knowledgeTracingService,
            @Nullable ResourceRecommendationLogRepository recommendationLogRepository,
            @Nullable LearningSessionRepository learningSessionRepository
    ) {
        this.hybridRecommendationEngine = hybridRecommendationEngine;
        this.resourceVectorIndexService = resourceVectorIndexService;
        this.knowledgeTracingService = knowledgeTracingService;
        this.recommendationLogRepository = recommendationLogRepository;
        this.learningSessionRepository = learningSessionRepository;
    }

    /** Test / legacy constructor without audit repositories. */
    public ResourceRecommendationService(
            HybridRecommendationEngine hybridRecommendationEngine,
            ResourceVectorIndexService resourceVectorIndexService
    ) {
        this(hybridRecommendationEngine, resourceVectorIndexService, null, null, null);
    }

    /** Test constructor with tracing only. */
    public ResourceRecommendationService(
            HybridRecommendationEngine hybridRecommendationEngine,
            ResourceVectorIndexService resourceVectorIndexService,
            KnowledgeTracingService knowledgeTracingService
    ) {
        this(hybridRecommendationEngine, resourceVectorIndexService, knowledgeTracingService, null, null);
    }

    /** Legacy test constructor without tracing. */
    public ResourceRecommendationService(
            HybridRecommendationEngine hybridRecommendationEngine,
            ResourceVectorIndexService resourceVectorIndexService,
            ResourceRecommendationLogRepository recommendationLogRepository,
            LearningSessionRepository learningSessionRepository
    ) {
        this(hybridRecommendationEngine, resourceVectorIndexService, null, recommendationLogRepository, learningSessionRepository);
    }

    public ResourceRecommendationResponse recommend(
            List<GeneratedArtifact> artifacts,
            String learnerProfileSnapshot,
            String weakPointsSnapshot,
            String cognitiveStyle,
            boolean recentQuizLow
    ) {
        return recommend(
                artifacts,
                learnerProfileSnapshot,
                weakPointsSnapshot,
                cognitiveStyle,
                recentQuizLow,
                null,
                null,
                "manual",
                null
        );
    }

    public ResourceRecommendationResponse recommend(
            List<GeneratedArtifact> artifacts,
            String learnerProfileSnapshot,
            String weakPointsSnapshot,
            String cognitiveStyle,
            boolean recentQuizLow,
            Long userId,
            Long learningSessionId,
            String pushSource,
            String pushMessage
    ) {
        if (artifacts == null || artifacts.isEmpty()) {
            return new ResourceRecommendationResponse(List.of(), List.of());
        }

        List<Long> allIds = artifacts.stream().map(GeneratedArtifact::getId).toList();
        String tracingWeakness = knowledgeTracingService != null && userId != null
                ? knowledgeTracingService.buildWeaknessQuery(userId, 6)
                : "";
        String query = joinNonBlank(tracingWeakness, weakPointsSnapshot, learnerProfileSnapshot, cognitiveStyle);
        Map<Long, Double> vectorScores = safeVectorScores(query, artifacts);
        LearningSession.LearningPhase learningPhase = resolveLearningPhase(learningSessionId);

        RecommendContext context = new RecommendContext(
                query,
                nullToEmpty(weakPointsSnapshot),
                nullToEmpty(cognitiveStyle),
                recentQuizLow,
                learningPhase,
                artifacts
        );

        List<ResourceRecommendationDto> recommendations = artifacts.stream()
                .map(artifact -> hybridRecommendationEngine.recommend(artifact, context, vectorScores))
                .filter(dto -> dto != null)
                .sorted(Comparator.comparingInt(ResourceRecommendationDto::score).reversed())
                .limit(TOP_N)
                .toList();

        logRecommendation(query, recommendations, userId, learningSessionId, pushSource, pushMessage);
        return new ResourceRecommendationResponse(recommendations, allIds);
    }

    private Map<Long, Double> safeVectorScores(String query, List<GeneratedArtifact> artifacts) {
        if (resourceVectorIndexService == null || query == null || query.isBlank()) {
            return Map.of();
        }
        try {
            return resourceVectorIndexService.scoreSimilarArtifacts(
                    query,
                    artifacts,
                    Math.max(TOP_N, artifacts.size())
            );
        } catch (Exception ex) {
            log.warn("Vector recall skipped, degrading to embedding/lexical tiers: {}", ex.getMessage());
            return Map.of();
        }
    }

    private LearningSession.LearningPhase resolveLearningPhase(Long learningSessionId) {
        if (learningSessionRepository == null || learningSessionId == null) {
            return null;
        }
        try {
            return learningSessionRepository.findById(learningSessionId)
                    .map(LearningSession::getCurrentPhase)
                    .orElse(null);
        } catch (Exception ex) {
            log.warn("Learning phase lookup skipped: {}", ex.getMessage());
            return null;
        }
    }

    private void logRecommendation(
            String query,
            List<ResourceRecommendationDto> recommendations,
            Long userId,
            Long learningSessionId,
            String pushSource,
            String pushMessage
    ) {
        if (recommendationLogRepository == null) {
            return;
        }
        try {
            ResourceRecommendationLog audit = new ResourceRecommendationLog();
            audit.setUserId(userId != null ? userId : 0L);
            audit.setLearningSessionId(learningSessionId);
            audit.setQueryText(query == null || query.isBlank() ? "EMPTY" : query);
            audit.setRecommendedIds(recommendations.stream()
                    .map(ResourceRecommendationDto::artifactId)
                    .map(String::valueOf)
                    .reduce((a, b) -> a + "," + b)
                    .orElse(""));
            audit.setIsFallback(recommendations.stream().noneMatch(this::usesVectorRetrieval));
            audit.setPushSource(pushSource != null ? pushSource : "manual");
            audit.setPushMessage(pushMessage);
            audit.setConsumed(false);
            recommendationLogRepository.save(audit);
        } catch (Exception ex) {
            log.warn("Recommendation audit log skipped: {}", ex.getMessage());
        }
    }

    private boolean usesVectorRetrieval(ResourceRecommendationDto recommendation) {
        String reason = recommendation.recommendationReason();
        return reason != null && (reason.contains("语义高度相关") || reason.contains("向量") || reason.contains("Embedding"));
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
}
