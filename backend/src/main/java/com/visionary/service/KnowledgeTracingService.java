package com.visionary.service;

import com.visionary.dto.KnowledgeTracingRadarDto;
import com.visionary.entity.LearningMetrics;
import com.visionary.repository.LearningMetricsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight, fully explainable knowledge-tracing service.
 * <p>
 * Estimates per-concept mastery confidence from practice accuracy and recency,
 * without external AI or opaque models. Intended for dashboards, remediation
 * routing, and audit-friendly defense materials.
 * </p>
 *
 * <h2>Heuristic confidence model</h2>
 * <p>Given aggregate counters on {@link LearningMetrics}:</p>
 * <ol>
 *   <li><b>Accuracy</b> (base mastery): {@code A = correctCount / totalAttempts}.
 *       Range [0, 1]. Zero attempts yields confidence 0 (undefined accuracy).</li>
 *   <li><b>Time decay</b>: {@code D = exp(-λ · Δt)} where {@code Δt} is whole days
 *       since {@code lastPracticedAt}, and {@code λ = 0.1} (≈37% retention at 10 days).
 *       Missing {@code lastPracticedAt} is treated as {@code Δt = 0} (no decay yet).</li>
 *   <li><b>Composite confidence</b>: {@code C = 0.6·A + 0.4·A·D}.
 *       60% weight on raw accuracy; 40% on decay-adjusted accuracy so stale knowledge
 *       scores lower while recent practice is rewarded.</li>
 *   <li><b>Clamp</b>: {@code C ∈ [0.0, 1.0]}.</li>
 * </ol>
 *
 * <p>Example: 8/10 correct, practiced today → A=0.8, D=1.0 → C=0.8.
 * Same accuracy but 30 days idle → D≈0.05 → C≈0.512.</p>
 */
@Service
@RequiredArgsConstructor
public class KnowledgeTracingService {

    static final int MIN_RADAR_CONCEPTS = 2;
    static final int MAX_RADAR_CONCEPTS = 8;
    private static final int VIRTUAL_QUESTIONS_PER_QUIZ = 10;
    private static final String DEFAULT_CONCEPT = "综合练习";

    private final LearningMetricsRepository metricsRepository;

    /** Weight on raw accuracy in the composite score. */
    private static final double ACCURACY_WEIGHT = 0.6;

    /** Weight on decay-adjusted accuracy in the composite score. */
    private static final double DECAYED_ACCURACY_WEIGHT = 0.4;

    /** Exponential decay rate λ (per day); higher = faster forgetting. */
    private static final double DECAY_LAMBDA = 0.1;

    /**
     * Computes explainable confidence for a knowledge-point aggregate.
     *
     * @param metrics non-null row with attempt counters; may have null {@code lastPracticedAt}
     * @return confidence in [0.0, 1.0]
     */
    public double calculateConfidence(LearningMetrics metrics) {
        if (metrics == null || metrics.getTotalAttempts() == null || metrics.getTotalAttempts() <= 0) {
            return 0.0;
        }

        int correct = metrics.getCorrectCount() == null ? 0 : metrics.getCorrectCount();
        double accuracy = (double) correct / metrics.getTotalAttempts();

        double daysSinceLastPractice = daysSince(metrics.getLastPracticedAt());
        double decay = Math.exp(-DECAY_LAMBDA * daysSinceLastPractice);

        double confidence = (ACCURACY_WEIGHT * accuracy) + (DECAYED_ACCURACY_WEIGHT * accuracy * decay);
        return clamp(confidence);
    }

    @Transactional
    public void recordPracticeAccuracy(Long userId, String concept, double accuracy) {
        if (userId == null) {
            return;
        }
        String label = concept == null || concept.isBlank() ? DEFAULT_CONCEPT : concept.trim();
        int correct = (int) Math.round(clamp(accuracy) * VIRTUAL_QUESTIONS_PER_QUIZ);
        upsertAttempts(userId, label, VIRTUAL_QUESTIONS_PER_QUIZ, correct);
    }

    public KnowledgeTracingRadarDto getRadarSnapshot(Long userId) {
        if (userId == null) {
            return new KnowledgeTracingRadarDto(List.of(), 0, true);
        }

        List<LearningMetrics> rows = metricsRepository.findByUserIdOrderByConfidenceScoreDesc(userId);
        List<KnowledgeTracingRadarDto.ConceptScore> concepts = new ArrayList<>();
        int meaningfulCount = 0;

        for (LearningMetrics row : rows) {
            if (row.getTotalAttempts() == null || row.getTotalAttempts() <= 0) {
                continue;
            }
            meaningfulCount++;
            double confidence = row.getConfidenceScore() != null
                    ? clamp(row.getConfidenceScore())
                    : calculateConfidence(row);
            concepts.add(new KnowledgeTracingRadarDto.ConceptScore(row.getKnowledgeConcept(), confidence));
            if (concepts.size() >= MAX_RADAR_CONCEPTS) {
                break;
            }
        }

        return new KnowledgeTracingRadarDto(
                concepts,
                meaningfulCount,
                concepts.size() < MIN_RADAR_CONCEPTS
        );
    }

    @Transactional
    public void recordQuizOutcome(Long userId, double accuracy, List<String> weakPoints) {
        recordPracticeAccuracy(userId, DEFAULT_CONCEPT, accuracy);
        if (weakPoints == null || weakPoints.isEmpty()) {
            return;
        }
        weakPoints.stream()
                .filter(point -> point != null && !point.isBlank())
                .map(String::trim)
                .distinct()
                .limit(5)
                .forEach(point -> upsertAttempts(userId, point, 1, 0));
    }

    public double confidenceForConcept(Long userId, String concept) {
        if (userId == null || concept == null || concept.isBlank()) {
            return 0D;
        }
        return metricsRepository.findByUserIdAndKnowledgeConcept(userId, concept.trim())
                .map(row -> row.getConfidenceScore() != null ? clamp(row.getConfidenceScore()) : calculateConfidence(row))
                .orElse(0D);
    }

    /**
     * Builds a semantic query fragment from low-confidence concepts for vector retrieval.
     */
    public String buildWeaknessQuery(Long userId, int limit) {
        if (userId == null) {
            return "";
        }
        List<LearningMetrics> rows = metricsRepository.findByUserIdOrderByConfidenceScoreDesc(userId);
        StringBuilder builder = new StringBuilder();
        int added = 0;
        for (LearningMetrics row : rows) {
            if (row.getKnowledgeConcept() == null || row.getKnowledgeConcept().isBlank()) {
                continue;
            }
            double confidence = row.getConfidenceScore() != null
                    ? clamp(row.getConfidenceScore())
                    : calculateConfidence(row);
            if (confidence >= 0.72D) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(row.getKnowledgeConcept());
            added++;
            if (added >= limit) {
                break;
            }
        }
        return builder.toString();
    }

    public List<String> weakConceptLabels(Long userId, int limit) {
        if (userId == null) {
            return List.of();
        }
        return metricsRepository.findByUserIdOrderByConfidenceScoreDesc(userId).stream()
                .filter(row -> row.getKnowledgeConcept() != null && !row.getKnowledgeConcept().isBlank())
                .filter(row -> {
                    double confidence = row.getConfidenceScore() != null
                            ? clamp(row.getConfidenceScore())
                            : calculateConfidence(row);
                    return confidence < 0.72D;
                })
                .map(LearningMetrics::getKnowledgeConcept)
                .limit(limit)
                .toList();
    }

    private void upsertAttempts(Long userId, String concept, int attemptsDelta, int correctDelta) {
        LearningMetrics row = metricsRepository.findByUserIdAndKnowledgeConcept(userId, concept)
                .orElseGet(() -> {
                    LearningMetrics created = new LearningMetrics();
                    created.setUserId(userId);
                    created.setKnowledgeConcept(concept);
                    created.setTotalAttempts(0);
                    created.setCorrectCount(0);
                    return created;
                });

        row.setTotalAttempts(row.getTotalAttempts() + Math.max(0, attemptsDelta));
        row.setCorrectCount(row.getCorrectCount() + Math.max(0, correctDelta));
        row.setLastPracticedAt(LocalDateTime.now());
        row.setConfidenceScore(calculateConfidence(row));
        metricsRepository.save(row);
    }

    private static double daysSince(LocalDateTime lastPracticedAt) {
        if (lastPracticedAt == null) {
            return 0.0;
        }
        long days = ChronoUnit.DAYS.between(lastPracticedAt, LocalDateTime.now());
        return Math.max(0, days);
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
