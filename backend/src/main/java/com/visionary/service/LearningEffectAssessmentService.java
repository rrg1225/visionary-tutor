package com.visionary.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.client.DeepSeekApiClient;
import com.visionary.entity.LearningEventMetric;
import com.visionary.repository.LearningEventMetricRepository;
import com.visionary.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Learning effect assessment with deterministic radar from recorded metrics.
 * LLM is used only for narrative suggestions when enough quantitative evidence exists.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LearningEffectAssessmentService {

    private static final int MIN_MEANINGFUL_METRICS = 2;
    private static final Set<String> NON_ASSESSMENT_TYPES = Set.of(
            "REPORT_VIEW", "CHAT_TURN", "PROFILE_EXTRACT"
    );

    private final LearningEventMetricRepository metricsRepository;
    private final UserRepository userRepository;
    private final DeepSeekApiClient deepSeekApiClient;
    private final ObjectMapper objectMapper;
    private final ReplanTriggerService replanTriggerService;
    private final KnowledgeTracingService knowledgeTracingService;

    @Transactional
    public void recordMetric(Long userId, Long sessionId, String type, String concept,
                             Double numericValue, String textValue, String source) {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("metric type is required");
        }
        String normalizedType = type.trim().toUpperCase(Locale.ROOT);
        LearningEventMetric m = LearningEventMetric.builder()
                .userId(userId)
                .learningSessionId(sessionId)
                .metricType(normalizedType)
                .concept(concept)
                .valueNumeric(numericValue)
                .valueText(textValue)
                .eventTime(Instant.now())
                .source(source)
                .build();
        metricsRepository.save(m);
        if ("QUIZ_ACCURACY".equals(normalizedType) && numericValue != null) {
            knowledgeTracingService.recordPracticeAccuracy(userId, concept, numericValue);
        }
    }

    @Transactional
    public void recordMasteryDelta(Long userId, Long sessionId, String concept,
                                   double before, double after, String source) {
        LearningEventMetric m = LearningEventMetric.builder()
                .userId(userId)
                .learningSessionId(sessionId)
                .metricType("MASTERY_DELTA")
                .concept(concept)
                .beforeValue(before)
                .afterValue(after)
                .valueNumeric(after - before)
                .eventTime(Instant.now())
                .source(source)
                .build();
        metricsRepository.save(m);
    }

    @Transactional
    public AssessmentResult assessAndRecommend(Long userId, Long learningSessionId) {
        String profile = userRepository.findById(userId)
                .map(u -> u.getLearnerProfileSnapshot() == null ? "{}" : u.getLearnerProfileSnapshot())
                .orElse("{}");

        List<LearningEventMetric> recent = metricsRepository.findByUserIdOrderByEventTimeDesc(userId);
        if (recent.size() > 50) {
            recent = recent.subList(0, 50);
        }

        MetricsDigest digest = digestMetrics(recent);
        String metricsSummary = digest.summaryText();
        List<MasteryCurvePoint> masteryCurve = buildMasteryCurve(recent);
        PrePostSummary prePostSummary = buildPrePostSummary(recent);

        if (digest.meaningfulCount() < MIN_MEANINGFUL_METRICS) {
            return new AssessmentResult(
                    "学习数据不足：请先完成至少一次互动练习、作业评测或资源反馈后再查看雷达图。",
                    metricsSummary,
                    new AssessmentDetail(
                            List.of(),
                            List.of(
                                    "完成题库互动并提交作答",
                                    "上传一次作业获取视觉评测",
                                    "在学习资源卡片中留下反馈"
                            ),
                            false,
                            "",
                            true,
                            digest.meaningfulCount(),
                            masteryCurve,
                            prePostSummary
                    ),
                    false
            );
        }

        List<RadarPoint> radar = buildDeterministicRadar(digest);
        boolean shouldReplan = digest.avgQuizAccuracy() < 0.55 || digest.avgMasteryGain() < 0;
        String replanReason = shouldReplan ? "近期练习正确率或掌握度提升偏低" : "";

        String summary;
        List<String> suggestions;
        boolean llmUsed = false;

        if (deepSeekApiClient.isConfigured()) {
            try {
                String raw = deepSeekApiClient.chat(narrativeSystemPrompt(), narrativePrompt(profile, metricsSummary, radar), false);
                JsonNode root = objectMapper.readTree(extractJson(raw));
                summary = root.path("summary").asText("已基于量化指标生成学习评估");
                suggestions = parseList(root.path("suggestions"));
                if (!root.path("shouldReplan").isMissingNode()) {
                    shouldReplan = shouldReplan || root.path("shouldReplan").asBoolean(false);
                }
                if (!root.path("replanReason").asText("").isBlank()) {
                    replanReason = root.path("replanReason").asText(replanReason);
                }
                llmUsed = true;
            } catch (Exception e) {
                log.warn("Learning effect narrative LLM call failed: {}", e.getMessage());
                summary = buildDeterministicSummary(digest);
                suggestions = buildDeterministicSuggestions(digest);
            }
        } else {
            summary = buildDeterministicSummary(digest);
            suggestions = buildDeterministicSuggestions(digest);
        }

        AssessmentResult result = new AssessmentResult(
                summary,
                metricsSummary,
                new AssessmentDetail(
                        radar,
                        suggestions,
                        shouldReplan,
                        replanReason,
                        false,
                        digest.meaningfulCount(),
                        masteryCurve,
                        prePostSummary
                ),
                llmUsed
        );

        if (shouldReplan && learningSessionId != null) {
            replanTriggerService.triggerAfterQuiz(
                    userId,
                    learningSessionId,
                    digest.avgQuizAccuracy(),
                    suggestions.stream().filter(s -> s.contains("薄弱")).toList(),
                    List.of("量化评估触发重规划"),
                    replanReason
            );
        }
        return result;
    }

    private List<RadarPoint> buildDeterministicRadar(MetricsDigest digest) {
        int quizScore = (int) Math.round(digest.avgQuizAccuracy() * 100);
        int masteryScore = (int) Math.round(Math.max(0, Math.min(100, 50 + digest.avgMasteryGain() * 2)));
        int resourceScore = (int) Math.round(Math.min(100, digest.feedbackCount() * 20D));
        int weakImprovement = digest.masteryGainCount() > 0 && digest.avgMasteryGain() > 0 ? masteryScore : Math.max(20, quizScore - 10);
        int paceScore = digest.quizAttempts() >= 3 ? Math.min(100, 55 + quizScore / 5) : 45;

        return List.of(
                new RadarPoint("知识掌握", masteryScore),
                new RadarPoint("练习正确率", quizScore),
                new RadarPoint("学习节奏", paceScore),
                new RadarPoint("薄弱点改善", weakImprovement),
                new RadarPoint("资源利用", resourceScore)
        );
    }

    private MetricsDigest digestMetrics(List<LearningEventMetric> metrics) {
        Map<String, List<Double>> accuracyByConcept = new HashMap<>();
        List<Double> masteryGains = new ArrayList<>();
        int feedbackCount = 0;
        int meaningfulCount = 0;

        for (LearningEventMetric metric : metrics) {
            String type = metric.getMetricType();
            if (type == null || NON_ASSESSMENT_TYPES.contains(type)) {
                continue;
            }
            meaningfulCount++;

            if ("QUIZ_ACCURACY".equals(type) && metric.getValueNumeric() != null) {
                accuracyByConcept.computeIfAbsent(
                        metric.getConcept() == null ? "综合" : metric.getConcept(),
                        key -> new ArrayList<>()
                ).add(metric.getValueNumeric());
            }
            if ("MASTERY_DELTA".equals(type) && metric.getBeforeValue() != null && metric.getAfterValue() != null) {
                masteryGains.add(metric.getAfterValue() - metric.getBeforeValue());
            }
            if ("FEEDBACK".equals(type)) {
                feedbackCount++;
            }
        }

        double avgQuiz = accuracyByConcept.values().stream()
                .flatMap(Collection::stream)
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0D);
        double avgGain = masteryGains.stream().mapToDouble(Double::doubleValue).average().orElse(0D);
        int quizAttempts = accuracyByConcept.values().stream().mapToInt(List::size).sum();

        StringBuilder summary = new StringBuilder();
        accuracyByConcept.forEach((concept, values) -> {
            double avg = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            summary.append("概念 ").append(concept).append(" 平均正确率: ")
                    .append(String.format("%.1f", avg * 100)).append("%\n");
        });
        if (!masteryGains.isEmpty()) {
            summary.append("掌握度平均变化: ").append(String.format("%+.1f", avgGain)).append("\n");
        }
        if (feedbackCount > 0) {
            summary.append("资源反馈次数: ").append(feedbackCount).append("\n");
        }
        if (summary.isEmpty()) {
            summary.append("暂无足够量化指标");
        }

        return new MetricsDigest(meaningfulCount, avgQuiz, avgGain, quizAttempts, masteryGains.size(), feedbackCount, summary.toString());
    }

    private List<MasteryCurvePoint> buildMasteryCurve(List<LearningEventMetric> metrics) {
        return metrics.stream()
                .filter(metric -> metric.getMetricType() != null)
                .filter(metric -> Set.of(
                        "QUIZ_ACCURACY", "MASTERY_DELTA", "PRE_TEST", "POST_TEST", "PATH_STEP_COMPLETED"
                ).contains(metric.getMetricType()))
                .sorted(Comparator.comparing(metric -> Optional.ofNullable(metric.getEventTime()).orElse(Instant.EPOCH)))
                .map(metric -> new MasteryCurvePoint(
                        metric.getConcept() == null || metric.getConcept().isBlank() ? "综合" : metric.getConcept(),
                        metric.getMetricType(),
                        resolveMasteryPercent(metric),
                        Optional.ofNullable(metric.getEventTime()).orElse(Instant.EPOCH)
                ))
                .toList();
    }

    private PrePostSummary buildPrePostSummary(List<LearningEventMetric> metrics) {
        List<Double> pre = new ArrayList<>();
        List<Double> post = new ArrayList<>();
        for (LearningEventMetric metric : metrics) {
            if (metric.getMetricType() == null || metric.getValueNumeric() == null) {
                continue;
            }
            if ("PRE_TEST".equals(metric.getMetricType())) {
                pre.add(normalizePercent(metric.getValueNumeric()));
            }
            if ("POST_TEST".equals(metric.getMetricType())) {
                post.add(normalizePercent(metric.getValueNumeric()));
            }
        }
        double preAvg = pre.stream().mapToDouble(Double::doubleValue).average().orElse(0D);
        double postAvg = post.stream().mapToDouble(Double::doubleValue).average().orElse(0D);
        boolean comparable = !pre.isEmpty() && !post.isEmpty();
        return new PrePostSummary(
                round1(preAvg),
                round1(postAvg),
                comparable ? round1(postAvg - preAvg) : 0D,
                pre.size(),
                post.size(),
                comparable
        );
    }

    private double resolveMasteryPercent(LearningEventMetric metric) {
        if ("MASTERY_DELTA".equals(metric.getMetricType()) && metric.getAfterValue() != null) {
            return normalizePercent(metric.getAfterValue());
        }
        if (metric.getValueNumeric() != null) {
            return normalizePercent(metric.getValueNumeric());
        }
        return 0D;
    }

    private double normalizePercent(double value) {
        double percent = Math.abs(value) <= 1D ? value * 100D : value;
        return Math.max(0D, Math.min(100D, percent));
    }

    private double round1(double value) {
        return Math.round(value * 10D) / 10D;
    }

    private String buildDeterministicSummary(MetricsDigest digest) {
        return String.format(
                "基于 %d 条有效学习指标：练习平均正确率 %.0f%%，掌握度平均变化 %+.1f。",
                digest.meaningfulCount(),
                digest.avgQuizAccuracy() * 100,
                digest.avgMasteryGain()
        );
    }

    private List<String> buildDeterministicSuggestions(MetricsDigest digest) {
        List<String> suggestions = new ArrayList<>();
        if (digest.avgQuizAccuracy() < 0.6) {
            suggestions.add("练习正确率偏低，建议回到讲义与导图复习后再做专项题库");
        }
        if (digest.avgMasteryGain() <= 0) {
            suggestions.add("掌握度提升有限，建议针对薄弱点生成代码实操与拓展阅读");
        }
        if (digest.feedbackCount() == 0) {
            suggestions.add("对生成的学习资源提交反馈，便于系统调整推送策略");
        }
        if (suggestions.isEmpty()) {
            suggestions.add("保持当前节奏，继续完成路径中的下一步资源");
        }
        return suggestions;
    }

    private String narrativeSystemPrompt() {
        return """
                你是学习效果解读助手。严格输出 JSON。
                你只能解释已提供的量化指标与雷达分值，禁止修改 radar 数值，禁止臆造新的百分比。
                输出字段：summary, suggestions(2-4条), shouldReplan, replanReason
                """;
    }

    private String narrativePrompt(String profile, String metricsSummary, List<RadarPoint> radar) {
        StringBuilder radarBlock = new StringBuilder();
        for (RadarPoint point : radar) {
            radarBlock.append("- ").append(point.axis()).append(": ").append(point.value()).append('\n');
        }
        return """
                学生画像（节选）：
                %s

                量化指标：
                %s

                已锁定的雷达分值（不得修改）：
                %s

                请给出 summary 与 suggestions。
                """.formatted(
                profile.length() > 800 ? profile.substring(0, 800) : profile,
                metricsSummary,
                radarBlock
        );
    }

    private List<String> parseList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> list.add(n.asText()));
        }
        return list;
    }

    private String extractJson(String raw) {
        if (raw == null) {
            return "{}";
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return (start >= 0 && end > start) ? raw.substring(start, end + 1) : raw;
    }

    private record MetricsDigest(
            int meaningfulCount,
            double avgQuizAccuracy,
            double avgMasteryGain,
            int quizAttempts,
            int masteryGainCount,
            int feedbackCount,
            String summaryText
    ) {}

    public record RadarPoint(String axis, int value) {}

    public record AssessmentDetail(
            List<RadarPoint> radarData,
            List<String> suggestions,
            boolean shouldReplan,
            String replanReason,
            boolean insufficientData,
            int meaningfulMetricCount,
            List<MasteryCurvePoint> masteryCurve,
            PrePostSummary prePostSummary
    ) {
        public AssessmentDetail(
                List<RadarPoint> radarData,
                List<String> suggestions,
                boolean shouldReplan,
                String replanReason,
                boolean insufficientData,
                int meaningfulMetricCount
        ) {
            this(radarData, suggestions, shouldReplan, replanReason, insufficientData, meaningfulMetricCount,
                    List.of(), PrePostSummary.empty());
        }
    }

    public record MasteryCurvePoint(
            String concept,
            String metricType,
            double masteryPercent,
            Instant eventTime
    ) {}

    public record PrePostSummary(
            double preTestAverage,
            double postTestAverage,
            double delta,
            int preTestCount,
            int postTestCount,
            boolean comparable
    ) {
        public static PrePostSummary empty() {
            return new PrePostSummary(0D, 0D, 0D, 0, 0, false);
        }
    }

    public record AssessmentResult(
            String summary,
            String metricsSummary,
            AssessmentDetail detail,
            boolean llmUsed
    ) {}
}
