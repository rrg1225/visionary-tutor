package com.visionary.service;

import com.visionary.entity.LearningEventMetric;
import com.visionary.repository.LearningEventMetricRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Closes the pre-test → learning → post-test → mastery-delta quantitative loop.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LearningMasteryPipelineService {

    private static final String DEFAULT_CONCEPT = "综合练习";

    private final LearningEventMetricRepository metricRepository;
    private final LearningEffectAssessmentService learningEffectAssessmentService;
    private final KnowledgeTracingService knowledgeTracingService;

    @Transactional
    public void onQuizSubmitted(
            Long userId,
            Long learningSessionId,
            double accuracy,
            List<String> weakPoints,
            String source
    ) {
        if (userId == null) {
            return;
        }
        String concept = resolveConcept(weakPoints);
        double clamped = clamp01(accuracy);

        learningEffectAssessmentService.recordMetric(
                userId, learningSessionId, "QUIZ_ACCURACY", concept,
                clamped, null, source != null ? source : "quiz_submission"
        );
        knowledgeTracingService.recordQuizOutcome(userId, clamped, weakPoints);

        Optional<LearningEventMetric> existingPre = findSessionMetric(
                userId, learningSessionId, concept, "PRE_TEST"
        );
        if (existingPre.isEmpty()) {
            learningEffectAssessmentService.recordMetric(
                    userId, learningSessionId, "PRE_TEST", concept,
                    clamped, "baseline_first_quiz", "mastery_pipeline"
            );
            log.info("[MasteryPipeline] PRE_TEST recorded user={} session={} concept={} score={}",
                    userId, learningSessionId, concept, clamped);
            return;
        }

        double preScore = existingPre.get().getValueNumeric() != null
                ? existingPre.get().getValueNumeric()
                : clamped;
        learningEffectAssessmentService.recordMetric(
                userId, learningSessionId, "POST_TEST", concept,
                clamped, "post_learning_quiz", "mastery_pipeline"
        );
        learningEffectAssessmentService.recordMasteryDelta(
                userId,
                learningSessionId,
                concept,
                preScore * 100D,
                clamped * 100D,
                "mastery_pipeline"
        );
        knowledgeTracingService.recordPracticeAccuracy(userId, concept, clamped);
        log.info("[MasteryPipeline] POST_TEST + delta user={} session={} concept={} {}% → {}%",
                userId, learningSessionId, concept,
                Math.round(preScore * 100), Math.round(clamped * 100));
    }

    @Transactional
    public void recordExplicitPreTest(Long userId, Long learningSessionId, String concept, double scorePercent) {
        if (userId == null) {
            return;
        }
        String label = blankToDefault(concept, DEFAULT_CONCEPT);
        double normalized = clamp01(scorePercent > 1D ? scorePercent / 100D : scorePercent);
        if (findSessionMetric(userId, learningSessionId, label, "PRE_TEST").isPresent()) {
            return;
        }
        learningEffectAssessmentService.recordMetric(
                userId, learningSessionId, "PRE_TEST", label,
                normalized, "explicit_pre_test", "mastery_pipeline"
        );
        knowledgeTracingService.recordPracticeAccuracy(userId, label, normalized);
    }

    @Transactional
    public void onPathStepCompleted(
            Long userId,
            Long learningSessionId,
            String stepTitle,
            int stepOrder
    ) {
        if (userId == null || learningSessionId == null) {
            return;
        }
        String concept = blankToDefault(stepTitle, "路径步骤" + stepOrder);
        double before = knowledgeTracingService.confidenceForConcept(userId, concept);
        knowledgeTracingService.recordPracticeAccuracy(userId, concept, Math.min(1D, before + 0.12D));
        double after = knowledgeTracingService.confidenceForConcept(userId, concept);
        if (after > before) {
            learningEffectAssessmentService.recordMasteryDelta(
                    userId,
                    learningSessionId,
                    concept,
                    before * 100D,
                    after * 100D,
                    "path_step_completed"
            );
        }
        learningEffectAssessmentService.recordMetric(
                userId,
                learningSessionId,
                "PATH_STEP_COMPLETED",
                concept,
                after,
                "step_order=" + stepOrder,
                "path_progress"
        );
    }

    private Optional<LearningEventMetric> findSessionMetric(
            Long userId,
            Long learningSessionId,
            String concept,
            String metricType
    ) {
        return metricRepository
                .findFirstByUserIdAndLearningSessionIdAndMetricTypeAndConceptOrderByEventTimeDesc(
                        userId, learningSessionId, metricType, concept
                );
    }

    private static String resolveConcept(List<String> weakPoints) {
        if (weakPoints == null || weakPoints.isEmpty()) {
            return DEFAULT_CONCEPT;
        }
        return weakPoints.stream()
                .filter(p -> p != null && !p.isBlank())
                .map(String::trim)
                .findFirst()
                .orElse(DEFAULT_CONCEPT);
    }

    private static double clamp01(double value) {
        return Math.max(0D, Math.min(1D, value));
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
