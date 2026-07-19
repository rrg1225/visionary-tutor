package com.visionary.service;

import com.visionary.entity.LearningEventMetric;
import com.visionary.entity.ResourceUsageRecord;
import com.visionary.repository.LearningEventMetricRepository;
import com.visionary.repository.ResourceUsageRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class LearningEffectExperimentService {

    private static final String DEFAULT_CONCEPT = "general";

    private final LearningEventMetricRepository metricRepository;
    private final ResourceUsageRecordRepository usageRepository;
    private final LearningEffectAssessmentService assessmentService;
    private final KnowledgeTracingService knowledgeTracingService;

    @Transactional
    public void recordPostTest(Long userId, Long learningSessionId, String concept, double scorePercent) {
        if (userId == null) {
            return;
        }
        String label = normalizeConcept(concept);
        double normalized = normalizeScore(scorePercent);
        assessmentService.recordMetric(
                userId,
                learningSessionId,
                "POST_TEST",
                label,
                normalized,
                "explicit_post_test",
                "learning_effect_experiment"
        );
        knowledgeTracingService.recordPracticeAccuracy(userId, label, normalized);

        metricRepository.findFirstByUserIdAndLearningSessionIdAndMetricTypeAndConceptOrderByEventTimeDesc(
                userId,
                learningSessionId,
                "PRE_TEST",
                label
        ).ifPresent(pre -> assessmentService.recordMasteryDelta(
                userId,
                learningSessionId,
                label,
                normalizeScore(pre.getValueNumeric()) * 100D,
                normalized * 100D,
                "learning_effect_experiment"
        ));
    }

    @Transactional(readOnly = true)
    public LearningEffectExperimentReport buildReport(Long userId, Long learningSessionId) {
        List<LearningEventMetric> metrics = loadMetrics(userId, learningSessionId);
        List<ResourceUsageRecord> usage = loadUsage(userId, learningSessionId);
        List<ConceptEffect> concepts = buildConceptEffects(userId, metrics);
        BehaviorEvidence behavior = buildBehaviorEvidence(usage);
        OverallEffect overall = buildOverall(concepts);
        List<String> evidenceLog = buildEvidenceLog(metrics, usage);
        String markdown = renderMarkdown(userId, learningSessionId, overall, concepts, behavior, evidenceLog);
        return new LearningEffectExperimentReport(
                userId,
                learningSessionId,
                Instant.now(),
                overall,
                concepts,
                behavior,
                evidenceLog,
                markdown
        );
    }

    public String exportMarkdown(Long userId, Long learningSessionId) {
        return buildReport(userId, learningSessionId).markdown();
    }

    private List<LearningEventMetric> loadMetrics(Long userId, Long learningSessionId) {
        List<LearningEventMetric> metrics = learningSessionId != null
                ? metricRepository.findByLearningSessionIdOrderByEventTimeDesc(learningSessionId)
                : metricRepository.findByUserIdOrderByEventTimeDesc(userId);
        return metrics.stream()
                .filter(metric -> userId == null || userId.equals(metric.getUserId()))
                .toList();
    }

    private List<ResourceUsageRecord> loadUsage(Long userId, Long learningSessionId) {
        if (userId == null) {
            return List.of();
        }
        if (learningSessionId != null) {
            return usageRepository.findByUserIdAndLearningSessionIdOrderByGmtCreatedDesc(userId, learningSessionId);
        }
        return usageRepository.findByUserIdOrderByGmtCreatedDesc(userId);
    }

    private List<ConceptEffect> buildConceptEffects(Long userId, List<LearningEventMetric> metrics) {
        Map<String, ConceptAccumulator> byConcept = new LinkedHashMap<>();
        for (LearningEventMetric metric : metrics) {
            String concept = normalizeConcept(metric.getConcept());
            ConceptAccumulator acc = byConcept.computeIfAbsent(concept, ignored -> new ConceptAccumulator());
            String type = metric.getMetricType() == null ? "" : metric.getMetricType();
            if ("PRE_TEST".equals(type) && metric.getValueNumeric() != null) {
                acc.pre.add(normalizeScore(metric.getValueNumeric()) * 100D);
            } else if ("POST_TEST".equals(type) && metric.getValueNumeric() != null) {
                acc.post.add(normalizeScore(metric.getValueNumeric()) * 100D);
            } else if ("MASTERY_DELTA".equals(type) && metric.getBeforeValue() != null && metric.getAfterValue() != null) {
                acc.pre.add(normalizeScore(metric.getBeforeValue()) * 100D);
                acc.post.add(normalizeScore(metric.getAfterValue()) * 100D);
            }
        }

        return byConcept.entrySet().stream()
                .map(entry -> {
                    String concept = entry.getKey();
                    ConceptAccumulator acc = entry.getValue();
                    double pre = average(acc.pre);
                    double post = average(acc.post);
                    double mastery = userId == null ? 0D : knowledgeTracingService.confidenceForConcept(userId, concept) * 100D;
                    return new ConceptEffect(
                            concept,
                            round1(pre),
                            round1(post),
                            round1(post - pre),
                            round1(mastery),
                            acc.pre.size(),
                            acc.post.size()
                    );
                })
                .filter(effect -> effect.preSamples() > 0 || effect.postSamples() > 0)
                .sorted(Comparator.comparing(ConceptEffect::delta).reversed())
                .toList();
    }

    private BehaviorEvidence buildBehaviorEvidence(List<ResourceUsageRecord> usage) {
        Map<String, Long> actions = new LinkedHashMap<>();
        int durationSeconds = 0;
        int errorPatternCount = 0;
        for (ResourceUsageRecord record : usage) {
            String action = normalizeAction(record.getActionType());
            actions.put(action, actions.getOrDefault(action, 0L) + 1L);
            durationSeconds += Optional.ofNullable(record.getDurationSeconds()).orElse(0);
            String feedback = record.getFeedback() == null ? "" : record.getFeedback().toLowerCase(Locale.ROOT);
            if (feedback.contains("error") || feedback.contains("wrong") || feedback.contains("mistake")) {
                errorPatternCount++;
            }
        }
        long viewCount = actions.getOrDefault("view", 0L);
        long completed = actions.getOrDefault("complete", 0L)
                + actions.getOrDefault("completed", 0L)
                + actions.getOrDefault("finish", 0L);
        double completionRate = viewCount == 0 ? 0D : completed / (double) viewCount;
        return new BehaviorEvidence(
                usage.size(),
                actions,
                durationSeconds,
                round1(completionRate * 100D),
                errorPatternCount,
                averageRevisitIntervalHours(usage)
        );
    }

    private OverallEffect buildOverall(List<ConceptEffect> concepts) {
        double pre = average(concepts.stream().map(ConceptEffect::preTestScore).toList());
        double post = average(concepts.stream().map(ConceptEffect::postTestScore).toList());
        double delta = post - pre;
        int improved = (int) concepts.stream().filter(effect -> effect.delta() > 0).count();
        return new OverallEffect(
                round1(pre),
                round1(post),
                round1(delta),
                improved,
                concepts.size()
        );
    }

    private List<String> buildEvidenceLog(List<LearningEventMetric> metrics, List<ResourceUsageRecord> usage) {
        List<String> evidence = new ArrayList<>();
        metrics.stream()
                .sorted(Comparator.comparing(metric -> Optional.ofNullable(metric.getEventTime()).orElse(Instant.EPOCH)))
                .limit(30)
                .forEach(metric -> evidence.add(String.format(
                        "%s %s %s %.1f",
                        metric.getEventTime(),
                        metric.getMetricType(),
                        normalizeConcept(metric.getConcept()),
                        metric.getValueNumeric() == null ? 0D : normalizeScore(metric.getValueNumeric()) * 100D
                )));
        usage.stream()
                .sorted(Comparator.comparing(record -> Optional.ofNullable(record.getGmtCreated()).orElse(LocalDateTime.MIN)))
                .limit(30)
                .forEach(record -> evidence.add(String.format(
                        "%s RESOURCE_%s artifact:%s duration:%ss",
                        record.getGmtCreated(),
                        normalizeAction(record.getActionType()).toUpperCase(Locale.ROOT),
                        record.getResourceId(),
                        Optional.ofNullable(record.getDurationSeconds()).orElse(0)
                )));
        return evidence;
    }

    private String renderMarkdown(
            Long userId,
            Long learningSessionId,
            OverallEffect overall,
            List<ConceptEffect> concepts,
            BehaviorEvidence behavior,
            List<String> evidenceLog
    ) {
        StringBuilder md = new StringBuilder();
        md.append("# VisionaryTutor Learning Effect Experiment\n\n");
        md.append("- userId: ").append(userId).append('\n');
        md.append("- learningSessionId: ").append(learningSessionId).append('\n');
        md.append("- generatedAt: ").append(Instant.now()).append("\n\n");
        md.append("## Overall\n\n");
        md.append("- preTestAverage: ").append(overall.preTestAverage()).append("%\n");
        md.append("- postTestAverage: ").append(overall.postTestAverage()).append("%\n");
        md.append("- delta: ").append(overall.delta()).append("%\n");
        md.append("- improvedConcepts: ").append(overall.improvedConcepts()).append("/")
                .append(overall.conceptCount()).append("\n\n");
        md.append("## Concept Mastery\n\n");
        md.append("| concept | pre | post | delta | mastery |\n");
        md.append("|---|---:|---:|---:|---:|\n");
        for (ConceptEffect concept : concepts) {
            md.append("| ").append(concept.concept())
                    .append(" | ").append(concept.preTestScore())
                    .append(" | ").append(concept.postTestScore())
                    .append(" | ").append(concept.delta())
                    .append(" | ").append(concept.masteryScore())
                    .append(" |\n");
        }
        md.append("\n## Behavior Evidence\n\n");
        md.append("- usageEvents: ").append(behavior.usageEvents()).append('\n');
        md.append("- totalDurationSeconds: ").append(behavior.totalDurationSeconds()).append('\n');
        md.append("- completionRate: ").append(behavior.completionRatePercent()).append("%\n");
        md.append("- avgRevisitIntervalHours: ").append(behavior.averageRevisitIntervalHours()).append('\n');
        md.append("- actionCounts: ").append(behavior.actionCounts()).append("\n\n");
        md.append("## Evidence Log\n\n");
        for (String item : evidenceLog) {
            md.append("- ").append(item).append('\n');
        }
        return md.toString();
    }

    private static double average(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return 0D;
        }
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0D);
    }

    private static double normalizeScore(Double value) {
        if (value == null) {
            return 0D;
        }
        return normalizeScore(value.doubleValue());
    }

    private static double normalizeScore(double value) {
        double normalized = Math.abs(value) <= 1D ? value : value / 100D;
        return Math.max(0D, Math.min(1D, normalized));
    }

    private static String normalizeConcept(String concept) {
        return concept == null || concept.isBlank() ? DEFAULT_CONCEPT : concept.trim();
    }

    private static String normalizeAction(String action) {
        return action == null || action.isBlank() ? "unknown" : action.trim().toLowerCase(Locale.ROOT);
    }

    private static double averageRevisitIntervalHours(List<ResourceUsageRecord> usage) {
        List<LocalDateTime> times = usage.stream()
                .map(ResourceUsageRecord::getGmtCreated)
                .filter(value -> value != null)
                .sorted()
                .toList();
        if (times.size() < 2) {
            return 0D;
        }
        List<Double> gaps = new ArrayList<>();
        for (int index = 1; index < times.size(); index++) {
            Duration gap = Duration.between(times.get(index - 1), times.get(index));
            gaps.add(gap.toMinutes() / 60D);
        }
        return round1(average(gaps));
    }

    private static double round1(double value) {
        return Math.round(value * 10D) / 10D;
    }

    private static final class ConceptAccumulator {
        private final List<Double> pre = new ArrayList<>();
        private final List<Double> post = new ArrayList<>();
    }

    public record LearningEffectExperimentReport(
            Long userId,
            Long learningSessionId,
            Instant generatedAt,
            OverallEffect overall,
            List<ConceptEffect> concepts,
            BehaviorEvidence behavior,
            List<String> evidenceLog,
            String markdown
    ) {
    }

    public record OverallEffect(
            double preTestAverage,
            double postTestAverage,
            double delta,
            int improvedConcepts,
            int conceptCount
    ) {
    }

    public record ConceptEffect(
            String concept,
            double preTestScore,
            double postTestScore,
            double delta,
            double masteryScore,
            int preSamples,
            int postSamples
    ) {
    }

    public record BehaviorEvidence(
            int usageEvents,
            Map<String, Long> actionCounts,
            int totalDurationSeconds,
            double completionRatePercent,
            int errorPatternCount,
            double averageRevisitIntervalHours
    ) {
    }
}
