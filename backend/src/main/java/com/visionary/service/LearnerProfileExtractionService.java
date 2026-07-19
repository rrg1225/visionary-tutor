package com.visionary.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.visionary.client.DeepSeekApiClient;
import com.visionary.dto.ProfileExtractionRequest;
import com.visionary.dto.ProfileExtractionResponse;
import com.visionary.entity.LearningEventMetric;
import com.visionary.entity.User;
import com.visionary.os.LearnerStateStore;
import com.visionary.os.LearningEvent;
import com.visionary.os.LearningEventBus;
import com.visionary.os.LearningEventType;
import com.visionary.repository.LearningEventMetricRepository;
import com.visionary.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LearnerProfileExtractionService {

    private final DeepSeekApiClient deepSeekApiClient;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final LearnerStateStore learnerStateStore;
    private final LearningEventBus learningEventBus;
    private final LearningEventMetricRepository learningEventMetricRepository;
    private final UserMemoryService userMemoryService;
    private final KnowledgeTracingService knowledgeTracingService;

    @Transactional
    public ProfileExtractionResponse extract(ProfileExtractionRequest request) {
        String previous = calibrateProfileWithBehavior(
                request.userId(),
                blankToDefault(request.previousProfileSnapshot(), "{}")
        );
        if (!deepSeekApiClient.isConfigured()) {
            return persistIfPossible(
                    request.userId(),
                    previous,
                    false,
                    "SKIPPED",
                    "DeepSeek 未配置，保留上一版画像；前端不会使用正则伪造画像。",
                    request.extractPhase()
            );
        }

        try {
            String raw = deepSeekApiClient.chat(
                    systemPrompt(blankToDefault(request.extractPhase(), "FULL")),
                    userPrompt(request, previous),
                    false
            );
            String json = normalizeProfileJson(extractJson(raw));
            String merged = calibrateProfileWithBehavior(request.userId(), mergeProfileJson(previous, json));
            return persistIfPossible(request.userId(), merged, true, "UPDATED", "画像已由大模型抽取，并经过置信度/冲突合并", request.extractPhase());
        } catch (Exception e) {
            log.warn("Learner profile extraction failed: {}", e.getMessage());
            return persistIfPossible(
                    request.userId(),
                    previous,
                    false,
                    "FAILED",
                    "画像抽取失败，保留上一版画像：" + e.getMessage(),
                    request.extractPhase()
            );
        }
    }

    private ProfileExtractionResponse persistIfPossible(
            Long userId,
            String snapshot,
            boolean llmUsed,
            String status,
            String message,
            String extractPhase
    ) {
        if (userId != null) {
            userRepository.findById(userId).ifPresent(user -> {
                user.setLearnerProfileSnapshot(snapshot);
                syncUserFields(user, snapshot);
                userRepository.save(user);
            });
            if ("UPDATED".equals(status)) {
                try {
                    syncMemoriesFromSnapshot(userId, snapshot, extractPhase);
                    var state = learnerStateStore != null
                            ? learnerStateStore.bumpProfileVersion(userId, snapshot, "对话/学习上下文触发画像更新")
                            : LearnerStateStore.LearnerStateView.empty();
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("status", status);
                    payload.put("extractPhase", blankToDefault(extractPhase, "FULL"));
                    if (learningEventBus != null) {
                        learningEventBus.publish(LearningEvent.of(
                                LearningEventType.PROFILE_UPDATED,
                                userId,
                                null,
                                state != null ? state.profileVersion() : 0,
                                payload
                        ));
                    }
                } catch (Exception e) {
                    log.warn("Profile state/event update failed after snapshot save: {}", e.getMessage());
                }
            }
        }
        return new ProfileExtractionResponse(snapshot, llmUsed, status, message);
    }

    private void syncUserFields(User user, String snapshot) {
        try {
            JsonNode root = objectMapper.readTree(snapshot);
            String goal = root.path("goal").path("value").asText("");
            if (!goal.isBlank()) {
                user.setLearningGoal(goal);
            }
            JsonNode emotion = root.path("emotionAttention").path("value");
            if (!emotion.isMissingNode() && !emotion.asText("").isBlank()) {
                user.setEmotionProfileSnapshot(emotion.asText());
            }
        } catch (Exception ignored) {
            // Snapshot remains the source of truth even if optional denormalization fails.
        }
    }

    private String systemPrompt(String phase) {
        if ("USER_TURN".equalsIgnoreCase(phase)) {
            return """
                    你是高等教育学习画像抽取智能体。只输出 JSON，不要输出 Markdown。
                    当前是学生刚发送的新消息，请优先更新 goal、knowledgeBase、weakPoints、learningPace、errorPatterns。
                    如果证据不足，value 写“待观察”，confidence 低于 0.5。
                    JSON 必须包含这些 key：
                    knowledgeBase, goal, cognitiveStyle, weakPoints, errorPatterns, learningPace, emotionAttention。
                    每个 key 的结构为 {"value":"...","evidence":["..."],"confidence":0.0}
                    """;
        }
        return """
                你是高等教育学习画像抽取智能体。只输出 JSON，不要输出 Markdown。
                你必须根据对话和作业反馈抽取画像，不得臆造未出现的信息。
                如果证据不足，value 写“待观察”，confidence 低于 0.5。
                JSON 必须包含这些 key：
                knowledgeBase, goal, cognitiveStyle, weakPoints, errorPatterns, learningPace, emotionAttention。
                每个 key 的结构为：
                {
                  "value": "字符串或字符串数组",
                  "evidence": ["来自输入的短证据句"],
                  "confidence": 0.0
                }
                Also output optional "knowledgeState": [{"concept":"...", "mastery":0-100, "status":"unknown|learning|mastered|regressed", "evidence":["..."], "confidence":0.0}].
                """;
    }

    private String userPrompt(ProfileExtractionRequest request, String previous) {
        String phaseNote = "USER_TURN".equalsIgnoreCase(request.extractPhase())
                ? "阶段：学生刚发言，请快速捕捉其目标、基础、节奏与薄弱点信号。"
                : "阶段：一轮完整对话或作业反馈后，请综合更新画像。";
        return """
                %s

                上一版画像：
                %s

                最近对话：
                %s

                作业评测摘要：
                %s

                情绪/专注信号：
                %s
                """.formatted(
                phaseNote,
                previous,
                blankToDefault(request.conversationText(), "无"),
                blankToDefault(request.assessmentSummary(), "无"),
                blankToDefault(request.emotionSnapshot(), "无")
        );
    }

    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("empty profile extraction response");
        }
        String trimmed = raw.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("profile extraction response is not JSON");
        }
        return trimmed.substring(start, end + 1);
    }

    private String normalizeProfileJson(String json) throws Exception {
        ObjectNode root = readObjectOrEmpty(json);
        for (String key : profileDimensionKeys()) {
            JsonNode node = root.path(key);
            ObjectNode dimension = node != null && node.isObject()
                    ? (ObjectNode) node.deepCopy()
                    : objectMapper.createObjectNode();
            if (!dimension.has("value")) {
                dimension.put("value", "待观察");
            }
            if ("weakPoints".equals(key) || "errorPatterns".equals(key)) {
                dimension.set("value", sanitizeConceptLabels(dimension.path("value")));
            }
            if (!dimension.has("evidence") || !dimension.path("evidence").isArray()) {
                dimension.set("evidence", objectMapper.createArrayNode());
            }
            if (!dimension.has("confidence") || !dimension.path("confidence").isNumber()) {
                dimension.put("confidence", hasInformativeValue(dimension.path("value")) ? 0.55D : 0.3D);
            } else {
                double confidence = dimension.path("confidence").asDouble();
                dimension.put("confidence", Math.max(0.0D, Math.min(1.0D, confidence)));
            }
            root.set(key, dimension);
        }
        JsonNode knowledgeState = root.path("knowledgeState");
        if (!knowledgeState.isMissingNode() && !knowledgeState.isArray()) {
            root.remove("knowledgeState");
        }
        return objectMapper.writeValueAsString(root);
    }

    private JsonNode sanitizeConceptLabels(JsonNode rawValue) {
        var values = objectMapper.createArrayNode();
        List<String> candidates = new ArrayList<>();
        if (rawValue != null && rawValue.isArray()) {
            rawValue.forEach(item -> candidates.add(item.asText("")));
        } else if (rawValue != null && !rawValue.isMissingNode() && !rawValue.isNull()) {
            candidates.addAll(Arrays.asList(rawValue.asText("").split("[、，,;；\\n]")));
        }
        candidates.stream()
                .map(String::trim)
                .filter(this::isConceptLabel)
                .distinct()
                .limit(8)
                .forEach(values::add);
        return values;
    }

    private boolean isConceptLabel(String value) {
        if (value == null || value.isBlank() || "待观察".equals(value) || value.length() > 60) {
            return false;
        }
        if (value.matches(".*[。！？!?].*")) {
            return false;
        }
        return !value.matches(".*(建议|推荐|请先|请完成|可以通过|下一步|继续学习|上传|重试|点击|生成资源).*" );
    }

    private String mergeProfileJson(String previous, String incoming) throws Exception {
        ObjectNode previousRoot = readObjectOrEmpty(previous);
        ObjectNode incomingRoot = readObjectOrEmpty(incoming);
        ObjectNode merged = previousRoot.deepCopy();
        String now = java.time.OffsetDateTime.now().toString();

        for (String key : profileDimensionKeys()) {
            JsonNode oldNode = previousRoot.path(key);
            JsonNode newNode = incomingRoot.path(key);
            merged.set(key, mergeDimension(key, oldNode, newNode, now));
        }
        merged.set("knowledgeState", mergeKnowledgeState(
                previousRoot.path("knowledgeState"),
                incomingRoot.path("knowledgeState"),
                now
        ));
        merged.put("profileVersion", 2);
        merged.put("updatedAt", now);
        merged.put("mergePolicy", "confidence_decay_with_conflict_log");
        return objectMapper.writeValueAsString(merged);
    }

    private String calibrateProfileWithBehavior(Long userId, String snapshot) {
        if (userId == null || learningEventMetricRepository == null) {
            return snapshot;
        }
        try {
            List<LearningEventMetric> recent = learningEventMetricRepository.findByUserIdOrderByEventTimeDesc(userId);
            if (recent == null || recent.isEmpty()) {
                return snapshot;
            }

            ObjectNode root = readObjectOrEmpty(snapshot);
            ArrayNode knowledgeState = objectMapper.createArrayNode();
            ArrayNode weakEvidence = objectMapper.createArrayNode();
            int used = 0;

            for (LearningEventMetric metric : recent.stream().limit(50).toList()) {
                String concept = metric.getConcept();
                if (concept == null || concept.isBlank()) {
                    continue;
                }
                String type = metric.getMetricType();
                Double numeric = metric.getValueNumeric();
                if ("QUIZ_ACCURACY".equals(type) && numeric != null) {
                    used++;
                    addKnowledgeState(knowledgeState, concept, boundedPercent(numeric * 100D),
                            "behavior:quiz_accuracy", 0.85D);
                    if (numeric < 0.60D) {
                        weakEvidence.add(concept + " quiz accuracy " + String.format("%.0f%%", numeric * 100D));
                    }
                    continue;
                }
                if ("MASTERY_DELTA".equals(type) && metric.getAfterValue() != null) {
                    used++;
                    addKnowledgeState(knowledgeState, concept, boundedPercent(metric.getAfterValue()),
                            "behavior:mastery_delta", 0.90D);
                    if (metric.getAfterValue() < 60D || (metric.getValueNumeric() != null && metric.getValueNumeric() < 0D)) {
                        weakEvidence.add(concept + " mastery needs reinforcement");
                    }
                    continue;
                }
                if (("PRE_TEST".equals(type) || "POST_TEST".equals(type)) && numeric != null) {
                    used++;
                    addKnowledgeState(knowledgeState, concept, boundedPercent(numeric * 100D),
                            "behavior:" + type.toLowerCase(), 0.88D);
                }
            }

            mergeKnowledgeTracingState(userId, knowledgeState);

            if (knowledgeState.isEmpty()) {
                return snapshot;
            }

            root.set("knowledgeState", knowledgeState);
            ObjectNode behavior = objectMapper.createObjectNode();
            behavior.put("metricCount", used);
            behavior.put("calibratedAt", java.time.OffsetDateTime.now().toString());
            behavior.put("policy", "llm_profile_plus_behavior_metrics_plus_knowledge_tracing");
            root.set("behaviorCalibration", behavior);

            if (!weakEvidence.isEmpty()) {
                ObjectNode weakPoints = root.path("weakPoints").isObject()
                        ? (ObjectNode) root.path("weakPoints").deepCopy()
                        : objectMapper.createObjectNode();
                weakPoints.put("value", "Behavior metrics indicate concepts that need reinforcement.");
                weakPoints.set("evidence", weakEvidence);
                weakPoints.put("confidence", 0.88D);
                root.set("weakPoints", weakPoints);
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("Behavior calibration skipped for profile: {}", e.getMessage());
            return snapshot;
        }
    }

    private void mergeKnowledgeTracingState(Long userId, ArrayNode knowledgeState) {
        if (userId == null || knowledgeTracingService == null) {
            return;
        }
        var radar = knowledgeTracingService.getRadarSnapshot(userId);
        if (radar == null || radar.concepts() == null) {
            return;
        }
        for (var concept : radar.concepts()) {
            if (concept == null || concept.concept() == null || concept.concept().isBlank()) {
                continue;
            }
            int mastery = (int) Math.round(concept.score() * 100D);
            addKnowledgeState(
                    knowledgeState,
                    concept.concept(),
                    mastery,
                    "knowledge_tracing:accuracy_decay_heuristic",
                    Math.max(0.75D, Math.min(0.95D, concept.score()))
            );
        }
    }

    private void addKnowledgeState(ArrayNode knowledgeState, String concept, int mastery, String source, double confidence) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("concept", concept);
        item.put("mastery", mastery);
        item.put("source", source);
        item.put("confidence", confidence);
        item.put("updatedAt", java.time.OffsetDateTime.now().toString());
        knowledgeState.add(item);
    }

    private int boundedPercent(double value) {
        return (int) Math.round(Math.max(0D, Math.min(100D, value)));
    }

    // 自适应置信度衰减因子 - 可根据学习行为动态调整
    private static final double BASE_DECAY_FACTOR = 0.98D;
    private static final double MIN_DECAY_FACTOR = 0.90D;
    private static final double MAX_DECAY_FACTOR = 0.995D;

    /**
     * 计算自适应置信度衰减因子 - 客观数学计算，不使用 LLM 评估
     * 如果有测验成绩，使用公式: new_confidence = 0.6 * old_confidence + 0.4 * quiz_score
     * 如果没有测验成绩，严格基于时间衰减因子 BASE_DECAY_FACTOR 进行数学计算
     *
     * @param oldNode    旧画像节点
     * @param key        维度key
     * @param quizScore  测验成绩 (0.0-1.0)，可为 null
     * @return 计算后的新置信度
     */
    private double calculateAdaptiveDecayFactor(JsonNode oldNode, String key, Double quizScore) {
        double oldConfidence = oldNode != null && oldNode.isObject()
                ? oldNode.path("confidence").asDouble(0.5D)
                : 0.5D;

        // 如果有测验成绩，使用客观公式计算新置信度
        if (quizScore != null && quizScore >= 0.0D && quizScore <= 1.0D) {
            // 公式: new_confidence = 0.6 * old_confidence + 0.4 * quiz_score
            return 0.6D * oldConfidence + 0.4D * quizScore;
        }

        // 没有测验成绩，严格基于 BASE_DECAY_FACTOR 进行数学衰减
        double factor = BASE_DECAY_FACTOR;

        // 根据维度类型调整：事实性知识衰减更快，认知风格衰减更慢
        factor = switch (key) {
            case "knowledgeBase", "weakPoints" -> Math.max(MIN_DECAY_FACTOR, factor - 0.02); // 知识状态变化快
            case "cognitiveStyle", "learningPace" -> Math.min(MAX_DECAY_FACTOR, factor + 0.01); // 认知风格相对稳定
            case "goal" -> Math.max(MIN_DECAY_FACTOR, factor - 0.01); // 学习目标可能变化
            default -> factor;
        };

        // 纯数学计算，不使用 LLM 评估置信度
        return Math.max(0.0D, Math.min(1.0D, oldConfidence * factor));
    }

    private ObjectNode mergeDimension(String key, JsonNode oldNode, JsonNode newNode, String now) {
        return mergeDimension(key, oldNode, newNode, now, null);
    }

    private ObjectNode mergeDimension(String key, JsonNode oldNode, JsonNode newNode, String now, Double quizScore) {
        ObjectNode oldObj = oldNode != null && oldNode.isObject()
                ? (ObjectNode) oldNode.deepCopy()
                : objectMapper.createObjectNode();
        ObjectNode newObj = newNode != null && newNode.isObject()
                ? (ObjectNode) newNode.deepCopy()
                : objectMapper.createObjectNode();

        JsonNode oldValue = oldObj.path("value");
        JsonNode newValue = newObj.path("value");
        double oldConfidence = oldObj.path("confidence").asDouble(0.0D);
        double newConfidence = newObj.path("confidence").asDouble(0.0D);
        boolean hasOld = hasInformativeValue(oldValue);
        boolean hasNew = hasInformativeValue(newValue);

        // 使用客观置信度算法计算新置信度（不使用 LLM 评估）
        double calculatedConfidence = calculateAdaptiveDecayFactor(oldNode, key, quizScore);

        ObjectNode winner;
        if (!hasOld && hasNew) {
            winner = newObj;
        } else if (hasOld && !hasNew) {
            winner = oldObj;
            // 使用客观计算的置信度
            winner.put("confidence", calculatedConfidence);
            winner.put("confidenceSource", quizScore != null ? "quiz_weighted_formula" : "time_decay_math");
        } else if (hasOld && hasNew && conflicts(oldValue, newValue)) {
            boolean acceptNew = newConfidence >= Math.max(0.55D, oldConfidence + 0.15D);
            winner = acceptNew ? newObj : oldObj;
            appendConflict(winner, key, oldValue, oldConfidence, newValue, newConfidence, now);
        } else if (hasOld && hasNew) {
            winner = oldObj;
            winner.set("value", mergeValues(oldValue, newValue));
            // 取客观计算置信度和新置信度中的较高值
            winner.put("confidence", Math.max(calculatedConfidence, newConfidence));
            winner.put("confidenceSource", quizScore != null ? "quiz_weighted_formula" : "time_decay_math");
            mergeEvidence(winner, newObj.path("evidence"));
        } else {
            winner = newObj;
        }

        winner.put("lastObservedAt", now);
        winner.put("updatePolicy", "objective_confidence_v3: source=" + (quizScore != null ? "quiz_weighted" : "time_decay"));
        return winner;
    }

    private ArrayNode mergeKnowledgeState(JsonNode oldState, JsonNode newState, String now) {
        java.util.LinkedHashMap<String, ObjectNode> byConcept = new java.util.LinkedHashMap<>();
        appendKnowledgeNodes(byConcept, oldState, now, false);
        appendKnowledgeNodes(byConcept, newState, now, true);
        ArrayNode merged = objectMapper.createArrayNode();
        byConcept.values().forEach(merged::add);
        return merged;
    }

    private void appendKnowledgeNodes(
            java.util.LinkedHashMap<String, ObjectNode> byConcept,
            JsonNode nodes,
            String now,
            boolean incoming
    ) {
        if (nodes == null || !nodes.isArray()) {
            return;
        }
        for (JsonNode node : nodes) {
            if (node == null || !node.isObject()) {
                continue;
            }
            String concept = node.path("concept").asText("").trim();
            if (concept.isBlank()) {
                continue;
            }
            String key = concept.toLowerCase(java.util.Locale.ROOT);
            ObjectNode candidate = (ObjectNode) node.deepCopy();
            candidate.put("concept", concept);
            candidate.put("lastObservedAt", now);
            candidate.put("statePolicy", "concept mastery is merged by confidence, with adaptive decay based on mastery level");

            // 根据掌握程度调整衰减因子
            double masteryLevel = candidate.path("mastery").asDouble(50D);
            double adaptiveDecay = calculateDecayForMastery(masteryLevel);
            candidate.put("adaptiveDecayFactor", adaptiveDecay);

            ObjectNode existing = byConcept.get(key);
            if (existing == null) {
                if (!incoming) {
                    candidate.put("confidence", Math.max(0D, candidate.path("confidence").asDouble(0D) * adaptiveDecay));
                }
                byConcept.put(key, candidate);
                continue;
            }
            double oldConfidence = existing.path("confidence").asDouble(0D);
            double newConfidence = candidate.path("confidence").asDouble(0D);
            if (incoming && newConfidence >= Math.max(0.55D, oldConfidence - 0.05D)) {
                mergeEvidence(candidate, existing.path("evidence"));
                byConcept.put(key, candidate);
            } else {
                existing.put("confidence", Math.max(0D, oldConfidence * adaptiveDecay));
                existing.put("decayFactor", adaptiveDecay);
                mergeEvidence(existing, candidate.path("evidence"));
            }
        }
    }

    /**
     * 根据掌握程度计算衰减因子
     * 已掌握的内容遗忘更慢，学习中/未知的内容遗忘更快
     */
    private double calculateDecayForMastery(double masteryLevel) {
        // mastery 0-100: 掌握程度越高，衰减越慢（置信度保持更久）
        if (masteryLevel >= 80) {
            return 0.995; // 已精通 - 缓慢衰减
        } else if (masteryLevel >= 60) {
            return 0.99;  // 基本掌握
        } else if (masteryLevel >= 40) {
            return 0.98;  // 学习中 - 正常衰减
        } else if (masteryLevel >= 20) {
            return 0.96;  // 初学 - 较快衰减
        } else {
            return 0.94;  // 未知 - 快速衰减需要更多证据
        }
    }

    private ObjectNode readObjectOrEmpty(String json) throws Exception {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        JsonNode node = objectMapper.readTree(json);
        return node.isObject() ? (ObjectNode) node : objectMapper.createObjectNode();
    }

    private boolean hasInformativeValue(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return false;
        }
        if (value.isArray()) {
            return value.size() > 0;
        }
        String text = value.asText("").trim();
        return !text.isBlank()
                && !"待观察".equals(text)
                && !"暂无".equals(text)
                && !"unknown".equalsIgnoreCase(text);
    }

    private boolean conflicts(JsonNode oldValue, JsonNode newValue) {
        if (oldValue == null || newValue == null) {
            return false;
        }
        return !canonical(oldValue).equals(canonical(newValue));
    }

    private JsonNode mergeValues(JsonNode oldValue, JsonNode newValue) {
        if (oldValue != null && oldValue.isArray() || newValue != null && newValue.isArray()) {
            ArrayNode array = objectMapper.createArrayNode();
            appendUnique(array, oldValue);
            appendUnique(array, newValue);
            return array;
        }
        return newValue != null && hasInformativeValue(newValue) ? newValue : oldValue;
    }

    private void appendUnique(ArrayNode array, JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return;
        }
        if (value.isArray()) {
            value.forEach(item -> appendUnique(array, item));
            return;
        }
        String text = value.asText("").trim();
        if (text.isBlank()) {
            return;
        }
        for (JsonNode existing : array) {
            if (existing.asText().equals(text)) {
                return;
            }
        }
        if (array.size() < 12) {
            array.add(text);
        }
    }

    private void mergeEvidence(ObjectNode target, JsonNode incomingEvidence) {
        ArrayNode evidence = target.withArray("evidence");
        appendUnique(evidence, incomingEvidence);
    }

    private void appendConflict(
            ObjectNode target,
            String key,
            JsonNode oldValue,
            double oldConfidence,
            JsonNode newValue,
            double newConfidence,
            String now
    ) {
        ArrayNode conflicts = target.withArray("conflicts");
        ObjectNode conflict = objectMapper.createObjectNode();
        conflict.put("dimension", key);
        conflict.set("previousValue", oldValue == null ? objectMapper.nullNode() : oldValue);
        conflict.put("previousConfidence", oldConfidence);
        conflict.set("candidateValue", newValue == null ? objectMapper.nullNode() : newValue);
        conflict.put("candidateConfidence", newConfidence);
        conflict.put("observedAt", now);
        conflict.put("resolution", newConfidence >= oldConfidence + 0.15D ? "accepted_candidate" : "kept_previous_until_more_evidence");
        conflicts.add(conflict);
        while (conflicts.size() > 5) {
            conflicts.remove(0);
        }
    }

    private String canonical(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return "";
        }
        if (value.isArray()) {
            StringBuilder builder = new StringBuilder();
            value.forEach(item -> builder.append(item.asText("").trim()).append('|'));
            return builder.toString().toLowerCase();
        }
        return value.asText("").trim().toLowerCase();
    }

    private void syncMemoriesFromSnapshot(Long userId, String snapshot, String extractPhase) {
        if (userMemoryService == null || userId == null) {
            return;
        }
        String context = "extractPhase=" + blankToDefault(extractPhase, "FULL");
        userMemoryService.syncFromProfileSnapshot(userId, null, snapshot, context);
    }

    private String[] profileDimensionKeys() {
        return new String[]{
                "knowledgeBase",
                "goal",
                "cognitiveStyle",
                "weakPoints",
                "errorPatterns",
                "learningPace",
                "emotionAttention"
        };
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    /**
     * 基于测验结果校准画像置信度（外部校验）。
     * 连续答对3题 → 对应知识点confidence上调；连续答错 → 下调。
     * 如果concept不存在则创建，存在则更新confidence。
     *
     * @param userId               用户ID（字符串格式）
     * @param conceptCorrectStreak 各concept连续答对次数
     * @param conceptWrongStreak   各concept连续答错次数
     */
    @Transactional
    public void calibrateFromQuizResults(String userId, Map<String, Integer> conceptCorrectStreak, Map<String, Integer> conceptWrongStreak) {
        log.info("[ProfileCalibration] 收到测验校准请求 userId={} correctStreaks={} wrongStreaks={}",
                userId, conceptCorrectStreak, conceptWrongStreak);

        if (userId == null || userId.isBlank()) {
            log.warn("[ProfileCalibration] 用户ID为空，跳过校准");
            return;
        }

        // 1. 解析userId并加载User
        Long uid;
        try {
            uid = Long.parseLong(userId);
        } catch (NumberFormatException e) {
            log.error("[ProfileCalibration] 无效的用户ID格式: {}", userId);
            return;
        }

        User user = userRepository.findById(uid).orElse(null);
        if (user == null) {
            log.warn("[ProfileCalibration] 未找到用户: {}", userId);
            return;
        }

        // 2. 解析现有的learnerProfileSnapshot
        String snapshot = blankToDefault(user.getLearnerProfileSnapshot(), "{}");
        ObjectNode root;
        ArrayNode knowledgeState;
        try {
            root = readObjectOrEmpty(snapshot);
            JsonNode existingState = root.path("knowledgeState");
            if (existingState.isArray()) {
                knowledgeState = (ArrayNode) existingState.deepCopy();
            } else {
                knowledgeState = objectMapper.createArrayNode();
            }
        } catch (Exception e) {
            log.error("[ProfileCalibration] 解析用户画像失败 userId={}: {}", userId, e.getMessage());
            return;
        }

        // 3. 构建concept到index的映射，便于快速查找
        java.util.Map<String, Integer> conceptIndexMap = new java.util.HashMap<>();
        for (int i = 0; i < knowledgeState.size(); i++) {
            JsonNode node = knowledgeState.get(i);
            if (node.isObject()) {
                String concept = node.path("concept").asText("").trim().toLowerCase(java.util.Locale.ROOT);
                if (!concept.isBlank()) {
                    conceptIndexMap.put(concept, i);
                }
            }
        }

        String now = java.time.OffsetDateTime.now().toString();
        int updatedCount = 0;
        int createdCount = 0;

        // 4. 处理正确答题的streak（提升confidence）
        if (conceptCorrectStreak != null) {
            for (java.util.Map.Entry<String, Integer> entry : conceptCorrectStreak.entrySet()) {
                String concept = entry.getKey();
                Integer streak = entry.getValue();
                if (concept == null || concept.isBlank() || streak == null || streak <= 0) {
                    continue;
                }

                String conceptKey = concept.trim().toLowerCase(java.util.Locale.ROOT);
                ObjectNode conceptNode;

                if (conceptIndexMap.containsKey(conceptKey)) {
                    // 已存在，更新confidence
                    int index = conceptIndexMap.get(conceptKey);
                    conceptNode = (ObjectNode) knowledgeState.get(index).deepCopy();
                    double oldConfidence = conceptNode.path("confidence").asDouble(0.5);
                    // 连续答对3题及以上，confidence上调（每次+0.15，最高1.0）
                    double adjustment = Math.min(streak / 3.0, 1.0) * 0.15;
                    double newConfidence = Math.min(1.0, oldConfidence + adjustment);
                    conceptNode.put("confidence", newConfidence);
                    conceptNode.put("calibrationSource", "quiz_correct_streak:" + streak);
                    // mastery也相应提升
                    int oldMastery = conceptNode.path("mastery").asInt(50);
                    int newMastery = Math.min(100, oldMastery + (int) (adjustment * 50));
                    conceptNode.put("mastery", newMastery);
                    knowledgeState.set(index, conceptNode);
                    updatedCount++;
                    log.debug("[ProfileCalibration] 提升concept '{}' confidence: {} -> {}, mastery: {} -> {}",
                            concept, String.format("%.2f", oldConfidence), String.format("%.2f", newConfidence),
                            oldMastery, newMastery);
                } else {
                    // 不存在，创建新的concept节点
                    conceptNode = objectMapper.createObjectNode();
                    conceptNode.put("concept", concept.trim());
                    // 初始confidence基于答对次数
                    double initialConfidence = Math.min(0.85, 0.5 + streak * 0.05);
                    conceptNode.put("confidence", initialConfidence);
                    conceptNode.put("mastery", boundedPercent(initialConfidence * 100));
                    conceptNode.put("source", "quiz_calibration_created");
                    conceptNode.put("calibrationSource", "quiz_correct_streak:" + streak);
                    conceptNode.put("createdAt", now);
                    conceptNode.put("updatedAt", now);
                    conceptNode.put("status", streak >= 3 ? "learning" : "unknown");
                    knowledgeState.add(conceptNode);
                    conceptIndexMap.put(conceptKey, knowledgeState.size() - 1);
                    createdCount++;
                    log.debug("[ProfileCalibration] 创建新concept '{}' with confidence={}", concept, initialConfidence);
                }
            }
        }

        // 5. 处理错误答题的streak（降低confidence）
        if (conceptWrongStreak != null) {
            for (java.util.Map.Entry<String, Integer> entry : conceptWrongStreak.entrySet()) {
                String concept = entry.getKey();
                Integer streak = entry.getValue();
                if (concept == null || concept.isBlank() || streak == null || streak <= 0) {
                    continue;
                }

                String conceptKey = concept.trim().toLowerCase(java.util.Locale.ROOT);
                ObjectNode conceptNode;

                if (conceptIndexMap.containsKey(conceptKey)) {
                    // 已存在，降低confidence
                    int index = conceptIndexMap.get(conceptKey);
                    conceptNode = (ObjectNode) knowledgeState.get(index).deepCopy();
                    double oldConfidence = conceptNode.path("confidence").asDouble(0.5);
                    // 连续答错2题及以上，confidence下调（每次-0.10，最低0.1）
                    double adjustment = Math.min(streak / 2.0, 1.0) * 0.10;
                    double newConfidence = Math.max(0.1, oldConfidence - adjustment);
                    conceptNode.put("confidence", newConfidence);
                    conceptNode.put("calibrationSource", "quiz_wrong_streak:" + streak);
                    // mastery也相应降低
                    int oldMastery = conceptNode.path("mastery").asInt(50);
                    int newMastery = Math.max(0, oldMastery - (int) (adjustment * 30));
                    conceptNode.put("mastery", newMastery);
                    // 标记为需要加强
                    if (streak >= 2) {
                        conceptNode.put("needsReinforcement", true);
                        conceptNode.put("status", "regressed");
                    }
                    knowledgeState.set(index, conceptNode);
                    updatedCount++;
                    log.debug("[ProfileCalibration] 降低concept '{}' confidence: {} -> {}, mastery: {} -> {}",
                            concept, String.format("%.2f", oldConfidence), String.format("%.2f", newConfidence),
                            oldMastery, newMastery);
                } else {
                    // 不存在但答错了，创建新的concept节点并标记为薄弱点
                    conceptNode = objectMapper.createObjectNode();
                    conceptNode.put("concept", concept.trim());
                    // 初始confidence较低
                    double initialConfidence = Math.max(0.2, 0.5 - streak * 0.05);
                    conceptNode.put("confidence", initialConfidence);
                    conceptNode.put("mastery", boundedPercent(initialConfidence * 100));
                    conceptNode.put("source", "quiz_calibration_weakness_detected");
                    conceptNode.put("calibrationSource", "quiz_wrong_streak:" + streak);
                    conceptNode.put("needsReinforcement", true);
                    conceptNode.put("isWeakPoint", true);
                    conceptNode.put("createdAt", now);
                    conceptNode.put("updatedAt", now);
                    conceptNode.put("status", "unknown");
                    knowledgeState.add(conceptNode);
                    conceptIndexMap.put(conceptKey, knowledgeState.size() - 1);
                    createdCount++;
                    log.debug("[ProfileCalibration] 创建薄弱点concept '{}' with confidence={}", concept, initialConfidence);
                }
            }
        }

        // 6. 更新root并保存
        root.set("knowledgeState", knowledgeState);
        root.put("lastCalibratedAt", now);
        root.put("calibrationPolicy", "quiz_streak_confidence_adjustment");

        try {
            String updatedSnapshot = objectMapper.writeValueAsString(root);
            user.setLearnerProfileSnapshot(updatedSnapshot);
            // 更新profileVersion
            user.setProfileVersion(user.getProfileVersion() != null ? user.getProfileVersion() + 1 : 1);
            userRepository.save(user);

            // 7. 记录操作日志和事件
            log.info("[ProfileCalibration] 完成校准 userId={} 更新={} 创建={} totalConcepts={}",
                    userId, updatedCount, createdCount, knowledgeState.size());

            // 发布画像更新事件
            if (learningEventBus != null) {
                Map<String, Object> payload = new HashMap<>();
                payload.put("calibrationType", "QUIZ_STREAK");
                payload.put("updatedConcepts", updatedCount);
                payload.put("createdConcepts", createdCount);
                payload.put("correctStreaks", conceptCorrectStreak != null ? conceptCorrectStreak.size() : 0);
                payload.put("wrongStreaks", conceptWrongStreak != null ? conceptWrongStreak.size() : 0);

                learningEventBus.publish(LearningEvent.of(
                        LearningEventType.PROFILE_UPDATED,
                        uid,
                        null,
                        user.getProfileVersion(),
                        payload
                ));
            }

            // 同步更新LearnerStateStore（如果可用）
            if (learnerStateStore != null) {
                try {
                    learnerStateStore.bumpProfileVersion(uid, updatedSnapshot,
                            "Quiz校准: 更新了" + updatedCount + "个concept, 创建了" + createdCount + "个concept");
                } catch (Exception e) {
                    log.warn("[ProfileCalibration] 更新LearnerStateStore失败: {}", e.getMessage());
                }
            }

        } catch (Exception e) {
            log.error("[ProfileCalibration] 保存校准后的画像失败 userId={}: {}", userId, e.getMessage(), e);
            throw new RuntimeException("保存校准后的画像失败", e);
        }
    }
}
