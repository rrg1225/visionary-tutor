package com.visionary.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.client.DeepSeekApiClient;
import com.visionary.entity.LearningSession;
import com.visionary.repository.LearningSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Independent LearningPathRePlanService.
 * Dynamically re-plans personalized learning path based on knowledgeState mastery,
 * weak points from diagnostics, learning pace, and emotion signals.
 * Callable after any learning event (diagnostic, quiz, resource feedback).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LearningPathRePlanService {

    private final ObjectMapper objectMapper;
    private final DeepSeekApiClient deepSeekApiClient;
    private final LearningSessionRepository learningSessionRepository;

    /**
     * Re-plan and return structured path. Non-persistent by default (caller decides storage).
     */
    public LearningPathPlan rePlan(Long learningSessionId, String currentProfileSnapshot, String emotionSnapshot) {
        LearningSession session = learningSessionRepository.findById(learningSessionId).orElse(null);
        String topic = session != null ? session.getTopic() : "当前课程";

        List<KnowledgeGap> gaps = extractKnowledgeGaps(currentProfileSnapshot);
        String pace = extractPace(currentProfileSnapshot);
        String emotion = emotionSnapshot != null ? emotionSnapshot : "正常";

        List<PathStep> steps = buildPrioritizedSteps(gaps, pace, emotion, topic);

        String rationale = buildRationale(gaps, pace, emotion, steps);
        rationale = enrichRationaleWithLlm(topic, steps, rationale);

        return new LearningPathPlan(
                topic,
                steps,
                rationale,
                estimateTotalHours(steps),
                gaps.isEmpty() ? "已掌握核心概念，进入综合应用阶段" : "聚焦薄弱点与知识状态更新",
                java.time.OffsetDateTime.now().toString()
        );
    }

    private List<KnowledgeGap> extractKnowledgeGaps(String profileJson) {
        List<KnowledgeGap> gaps = new ArrayList<>();
        if (profileJson == null || profileJson.isBlank()) return gaps;
        try {
            JsonNode root = objectMapper.readTree(profileJson);
            JsonNode state = root.path("knowledgeState");
            if (state.isArray()) {
                for (JsonNode node : state) {
                    String concept = node.path("concept").asText("");
                    int mastery = node.path("mastery").asInt(0);
                    String status = node.path("status").asText("unknown");
                    double confidence = node.path("confidence").asDouble(0.0);
                    if (!concept.isBlank() && (mastery < 70 || "regressed".equalsIgnoreCase(status) || "unknown".equalsIgnoreCase(status))) {
                        gaps.add(new KnowledgeGap(concept, mastery, status, confidence));
                    }
                }
            }
            // also consider weakPoints dimension
            JsonNode weak = root.path("weakPoints").path("value");
            if (weak.isArray()) {
                for (JsonNode w : weak) {
                    String wp = w.asText("");
                    if (!wp.isBlank() && gaps.stream().noneMatch(g -> g.concept().toLowerCase().contains(wp.toLowerCase()))) {
                        gaps.add(new KnowledgeGap(wp, 40, "weak", 0.6));
                    }
                }
            }
        } catch (Exception ignored) {
            // fall back to empty gaps
        }
        gaps.sort(Comparator.comparingInt(KnowledgeGap::mastery));
        return gaps;
    }

    private String extractPace(String profileJson) {
        if (profileJson == null || profileJson.isBlank()) return "适中";
        try {
            JsonNode root = objectMapper.readTree(profileJson);
            return root.path("learningPace").path("value").asText("适中");
        } catch (Exception e) {
            return "适中";
        }
    }

    private List<PathStep> buildPrioritizedSteps(List<KnowledgeGap> gaps, String pace, String emotion, String topic) {
        List<PathStep> steps = new ArrayList<>();
        int baseMinutes = switch (pace.toLowerCase()) {
            case "慢", "slow" -> 25;
            case "快", "fast" -> 12;
            default -> 18;
        };
        boolean preferVisual = emotion.toLowerCase().contains("低") || emotion.toLowerCase().contains("焦虑");

        int order = 1;
        for (KnowledgeGap gap : gaps.stream().limit(6).toList()) {
            String recommendedType = preferVisual ? "VISUALIZATION" : (gap.mastery() < 50 ? "HANDOUT" : "QUIZ");
            int minutes = Math.max(10, (int) (baseMinutes * (100 - gap.mastery()) / 60.0));
            String rationale = "掌握度" + gap.mastery() + "%，状态" + gap.status() + "，优先补齐";
            steps.add(new PathStep(order++, gap.concept(), recommendedType, minutes, rationale, gap.mastery()));
        }

        if (steps.isEmpty()) {
            steps.add(new PathStep(1, topic + " 综合应用", "CODE_PRACTICE", 30, "核心已掌握，进入项目实践", 85));
        }
        return steps;
    }

    private String buildRationale(List<KnowledgeGap> gaps, String pace, String emotion, List<PathStep> steps) {
        if (gaps.isEmpty()) {
            return "画像显示知识状态良好，推荐进入综合应用与拓展阶段。";
        }
        String topConcepts = gaps.stream().limit(3).map(KnowledgeGap::concept).collect(Collectors.joining("、"));
        return "检测到 " + gaps.size() + " 个知识缺口（" + topConcepts + "），学习节奏" + pace + "，情绪信号" + emotion + "，已生成 " + steps.size() + " 步优先路径。";
    }

    private double estimateTotalHours(List<PathStep> steps) {
        int totalMin = steps.stream().mapToInt(PathStep::estimatedMinutes).sum();
        return Math.round(totalMin / 60.0 * 10) / 10.0;
    }

    private String enrichRationaleWithLlm(String topic, List<PathStep> steps, String ruleRationale) {
        if (!deepSeekApiClient.isConfigured()) {
            return ruleRationale;
        }
        try {
            String stepSummary = steps.stream()
                    .map(step -> step.order() + ". " + step.concept() + " -> " + step.recommendedResourceType())
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("无步骤");
            return deepSeekApiClient.chat(
                    "你是 PathAgent 助手。基于规则引擎输出的步骤，用 2-3 句中文解释为何这样排序。不要编造新步骤。",
                    "课程主题：" + topic + "\n规则 rationale：" + ruleRationale + "\n步骤：\n" + stepSummary,
                    false
            );
        } catch (Exception e) {
            log.warn("Path rationale LLM enrichment failed: {}", e.getMessage());
            return ruleRationale;
        }
    }

    // Lightweight records for clean output
    public record KnowledgeGap(String concept, int mastery, String status, double confidence) {}
    public record PathStep(int order, String concept, String recommendedResourceType, int estimatedMinutes, String rationale, int currentMastery) {}
    public record LearningPathPlan(String topic, List<PathStep> steps, String overallRationale, double estimatedHours, String nextMilestone, String generatedAt) {}
}
