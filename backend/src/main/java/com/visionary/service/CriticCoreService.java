package com.visionary.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.visionary.client.DeepSeekApiClient;
import com.visionary.config.AgentOrchestrationProperties;
import com.visionary.config.GovernanceProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CriticCoreService - 质量审查核心服务
 * 统一封装审查逻辑：构造Prompt、调用LLM、解析判决（PASS/REVISE）
 * 供 CriticAgent 和 CriticReviewTool 共享使用，消除代码重复
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CriticCoreService {

    private final DeepSeekApiClient deepSeekApiClient;
    private final ObjectMapper objectMapper;
    private final AgentOrchestrationProperties orchestrationProps;
    private final GovernanceProperties governanceProperties;

    /**
     * 执行标准内容审查（事实性检查 + 质量评估）
     *
     * @param topic 主题
     * @param content 待审查内容
     * @param learnerProfile 学生画像
     * @param ragEvidenceBlock RAG证据块
     * @param revisionRound 当前返修轮次
     * @return 审查结果
     */
    public CriticResult reviewContent(
            String topic,
            String content,
            String learnerProfile,
            String ragEvidenceBlock,
            int revisionRound) {

        log.info("[CriticCore] Reviewing content: topic='{}', revisionRound={}", topic, revisionRound);

        // Bounded-Revision 检查
        int maxRevisionRounds = governanceProperties.getMaxRevisionRounds();
        if (revisionRound >= maxRevisionRounds) {
            log.warn("[CriticCore] Max revision rounds ({}) reached, requiring manual review", maxRevisionRounds);
            return buildManualReviewResult(content, revisionRound, "Exceeded maximum revision rounds (" + maxRevisionRounds + ")");
        }

        if (!deepSeekApiClient.isConfigured()) {
            log.warn("[CriticCore] DeepSeek not configured, using fallback");
            return buildFallbackResult(content);
        }

        // 第一层：事实性检查
        FactualityResult factuality = runFactualityCheck(topic, content, ragEvidenceBlock);

        // 第二层：综合质量审查
        CriticReport report = runQualityReview(topic, content, learnerProfile, ragEvidenceBlock, factuality);

        // 判断是否需要返修
        boolean needsRevision = determineNeedsRevision(report, factuality);

        // 构建 reflection reason
        String reflectionReason = buildReflectionReason(report, factuality);

        return new CriticResult(
                report.verdict,
                report.critique,
                reflectionReason,
                report.missingCitationIds,
                report.factualErrors,
                factuality.score,
                factuality.hallucinationLog,
                needsRevision,
                revisionRound + (needsRevision ? 1 : 0),
                buildMetadata(report, factuality)
        );
    }

    /**
     * 执行资源集审查（多资源质量评估）
     *
     * @param topic 主题
     * @param resourceSummaries 资源摘要列表（JSON字符串）
     * @param learnerProfile 学生画像
     * @param reviewFocus 审查重点（COVERAGE/QUALITY/DIFFICULTY/ALL）
     * @return 审查结果
     */
    public ResourceReviewResult reviewResources(
            String topic,
            String resourceSummaries,
            String learnerProfile,
            String reviewFocus) {

        log.info("[CriticCore] Reviewing resources: topic='{}', focus='{}'", topic, reviewFocus);

        if (!deepSeekApiClient.isConfigured()) {
            return buildResourceFallbackResult(topic);
        }

        String systemPrompt = buildResourceReviewSystemPrompt();
        String userPrompt = buildResourceReviewPrompt(topic, resourceSummaries, learnerProfile, reviewFocus);

        try {
            String raw = deepSeekApiClient.chat(systemPrompt, userPrompt, false);
            return parseResourceReviewResult(raw);
        } catch (Exception e) {
            log.error("[CriticCore] Resource review failed: {}", e.getMessage());
            return buildResourceFallbackResult(topic);
        }
    }

    // ==================== 私有方法：事实性检查 ====================

    private FactualityResult runFactualityCheck(String topic, String content, String ragBlock) {
        double factualityScore = orchestrationProps.getFactualityDefaultScore();
        List<String> factualErrors = new ArrayList<>();
        List<String> hallucinationLog = new ArrayList<>();

        try {
            String rawFact = deepSeekApiClient.chat(
                    "你是 FactualityVerifier，独立的事实性审查 Judge。只输出 JSON。",
                    buildFactualityPrompt(topic, content, ragBlock),
                    false
            );
            String jsonFact = normalizeFactualityJson(extractJson(rawFact));
            JsonNode factRoot = objectMapper.readTree(jsonFact);
            factualityScore = boundedScore(factRoot.path("factualityScore").asDouble(0.8));
            if (factRoot.has("factualErrors")) {
                factRoot.path("factualErrors").forEach(n -> factualErrors.add(n.asText()));
            }
            if (factRoot.has("hallucinationLog")) {
                factRoot.path("hallucinationLog").forEach(n -> hallucinationLog.add(n.asText()));
            }
        } catch (Exception e) {
            log.warn("[CriticCore] Factuality check failed: {}", e.getMessage());
            factualityScore = orchestrationProps.getFactualityFallbackScore();
            hallucinationLog.add("FactualityVerifier failed, using fallback score");
        }

        return new FactualityResult(factualityScore, factualErrors, hallucinationLog);
    }

    private String buildFactualityPrompt(String topic, String content, String ragBlock) {
        return """
                主题：%s

                待审查内容：
                %s

                可用证据（RAG）：
                %s

                你是独立的事实性审查 Judge。请严格检查以下内容是否存在：
                - 事实性错误（概念定义、公式、计算结果、因果关系）
                - 概念混淆（把 A 说成 B）
                - 幻觉内容（证据不足却自信断言）

                输出 JSON（factualityScore 为 0.0~1.0 的置信度，1.0 表示完全可信）：
                {
                  "factualityScore": 0.92,
                  "factualErrors": ["具体错误1", "具体错误2"],
                  "hallucinationLog": ["第X句可能幻觉：..."]
                }
                """.formatted(topic, content, ragBlock != null ? ragBlock : "无证据");
    }

    // ==================== 私有方法：质量审查 ====================

    private CriticReport runQualityReview(
            String topic,
            String content,
            String learnerProfile,
            String ragBlock,
            FactualityResult factuality) {

        String systemPrompt = """
                你是 CriticAgent（质量审查专家）。

                审查原则：
                1. 证据一致性：内容中引用的 citationId 是否存在于 RAG 证据中？
                2. 事实准确性：公式、代码、定义、因果关系是否正确？
                3. 画像适配性：是否符合学生画像的薄弱点和认知风格？

                输出要求：
                - 只输出 JSON，不要 Markdown
                - verdict 必须是 PASS 或 REVISE

                输出 JSON 格式：
                {
                  "verdict": "PASS 或 REVISE",
                  "critique": "一句话总结审查结论",
                  "reflection_reason": "具体的返修指导",
                  "missingCitationIds": ["cite-xxx"],
                  "factualErrors": ["具体错误描述"]
                }
                """;

        String userPrompt = """
                主题：%s
                学生画像：%s

                待审查内容：
                %s

                可用证据（RAG）：
                %s

                事实性评分参考：%.2f

                请严格审查上述内容并输出 JSON 格式的审查报告。
                """.formatted(
                topic,
                learnerProfile != null ? learnerProfile : "{}",
                content,
                ragBlock != null ? ragBlock : "无证据",
                factuality.score
        );

        try {
            String raw = deepSeekApiClient.chat(systemPrompt, userPrompt, false);
            return parseCriticReport(extractJson(raw), factuality);
        } catch (Exception e) {
            log.error("[CriticCore] Quality review failed: {}", e.getMessage());
            CriticReport fallback = new CriticReport();
            fallback.verdict = "REVISE";
            fallback.critique = "审查执行失败，建议返修";
            fallback.reflectionReason = "Critic review failed: " + e.getMessage();
            return fallback;
        }
    }

    // ==================== 私有方法：资源集审查 ====================

    private String buildResourceReviewSystemPrompt() {
        return """
                你是CriticAgent，负责审查多智能体生成的教育资源质量。

                审查维度：
                1. COVERAGE（覆盖度）：是否涵盖主题的完整知识图谱？薄弱点是否被充分覆盖？
                2. QUALITY（质量）：内容准确性、引用可靠性、代码可运行性
                3. DIFFICULTY（难度）：是否符合学生画像的能力水平？梯度是否合理？
                4. CONSISTENCY（一致性）：各资源之间是否相互印证、无矛盾？

                输出结构（JSON）：
                {
                    "verdict": "PASS" | "REVISE" | "REJECT",
                    "scores": {
                        "coverage": 0-100,
                        "quality": 0-100,
                        "difficulty_match": 0-100,
                        "consistency": 0-100
                    },
                    "strengths": ["优点1", "优点2"],
                    "issues": [{
                        "severity": "critical" | "major" | "minor",
                        "resource_type": "QUIZ|HANDOUT|...",
                        "description": "问题描述",
                        "suggestion": "改进建议"
                    }],
                    "revision_plan": {
                        "need_supplement": true/false,
                        "supplement_types": ["需要补充的资源类型"],
                        "priority": "high" | "medium" | "low"
                    },
                    "summary": "一句话总结"
                }

                严格规则：
                - 有critical问题必须REVISE
                - 多个major问题必须REVISE
                - PASS意味着可以直接给学生使用
                """;
    }

    private String buildResourceReviewPrompt(String topic, String resources, String profile, String focus) {
        return """
                审查主题：%s

                已有资源摘要：
                %s

                学生画像：%s

                审查重点：%s

                请输出结构化审查报告。
                """.formatted(
                topic,
                resources != null ? resources : "[]",
                profile != null ? profile.substring(0, Math.min(profile.length(), 400)) : "{}",
                focus != null ? focus : "ALL"
        );
    }

    // ==================== 私有方法：结果解析 ====================

    private CriticReport parseCriticReport(String json, FactualityResult factuality) {
        try {
            String normalized = normalizeCriticJson(json);
            JsonNode root = objectMapper.readTree(normalized);

            CriticReport report = new CriticReport();
            report.verdict = root.path("verdict").asText("PASS");
            report.critique = root.path("critique").asText("");
            report.reflectionReason = root.path("reflection_reason").asText("");

            if (root.has("missingCitationIds")) {
                root.path("missingCitationIds").forEach(n -> report.missingCitationIds.add(n.asText()));
            }
            if (root.has("factualErrors")) {
                root.path("factualErrors").forEach(n -> report.factualErrors.add(n.asText()));
            }

            // 如果 reflection_reason 为空，基于事实性检查生成
            if (report.reflectionReason.isEmpty()) {
                report.reflectionReason = buildReflectionReason(report, factuality);
            }

            return report;
        } catch (Exception e) {
            log.error("[CriticCore] Parse critic report failed: {}", e.getMessage());
            CriticReport fallback = new CriticReport();
            fallback.verdict = "REVISE";
            fallback.critique = "解析审查结果失败，建议返修";
            fallback.reflectionReason = "审查报告解析异常，建议检查内容质量";
            return fallback;
        }
    }

    private ResourceReviewResult parseResourceReviewResult(String raw) {
        try {
            String json = extractJson(raw);
            JsonNode root = objectMapper.readTree(json);

            ResourceReviewResult result = new ResourceReviewResult();
            result.verdict = root.path("verdict").asText("PASS");
            result.summary = root.path("summary").asText("");

            JsonNode scores = root.path("scores");
            result.coverageScore = scores.path("coverage").asInt(75);
            result.qualityScore = scores.path("quality").asInt(80);
            result.difficultyMatchScore = scores.path("difficulty_match").asInt(70);
            result.consistencyScore = scores.path("consistency").asInt(75);

            if (root.has("strengths")) {
                root.path("strengths").forEach(n -> result.strengths.add(n.asText()));
            }
            if (root.has("issues")) {
                root.path("issues").forEach(n -> {
                    IssueInfo issue = new IssueInfo();
                    issue.severity = n.path("severity").asText("minor");
                    issue.resourceType = n.path("resource_type").asText("");
                    issue.description = n.path("description").asText("");
                    issue.suggestion = n.path("suggestion").asText("");
                    result.issues.add(issue);
                });
            }

            JsonNode revisionPlan = root.path("revision_plan");
            result.needSupplement = revisionPlan.path("need_supplement").asBoolean(false);
            if (revisionPlan.has("supplement_types")) {
                revisionPlan.path("supplement_types").forEach(n -> result.supplementTypes.add(n.asText()));
            }
            result.priority = revisionPlan.path("priority").asText("low");

            return result;
        } catch (Exception e) {
            log.error("[CriticCore] Parse resource review failed: {}", e.getMessage());
            return buildResourceFallbackResult("unknown");
        }
    }

    // ==================== 私有方法：辅助工具 ====================

    private String extractJson(String raw) {
        if (raw == null) return "{}";
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return raw;
    }

    private String normalizeCriticJson(String json) throws Exception {
        ObjectNode root = readObjectOrEmpty(json);
        ArrayNode warnings = objectMapper.createArrayNode();

        String verdict = root.path("verdict").asText("PASS");
        if (!"PASS".equalsIgnoreCase(verdict)
                && !"REVISE".equalsIgnoreCase(verdict)
                && !"REJECT".equalsIgnoreCase(verdict)) {
            warnings.add("verdict normalized to REVISE");
            verdict = "REVISE";
        }
        root.put("verdict", verdict.toUpperCase(Locale.ROOT));

        if (!root.has("critique") || root.path("critique").asText("").isBlank()) {
            root.put("critique", "Critic output did not include a critique; manual review recommended.");
            warnings.add("missing critique");
        }
        ensureArray(root, "missingCitationIds", warnings);
        ensureArray(root, "factualErrors", warnings);
        ensureArray(root, "schemaWarnings", warnings);
        ArrayNode schemaWarnings = (ArrayNode) root.get("schemaWarnings");
        warnings.forEach(schemaWarnings::add);
        return objectMapper.writeValueAsString(root);
    }

    private String normalizeFactualityJson(String json) throws Exception {
        ObjectNode root = readObjectOrEmpty(json);
        ArrayNode warnings = objectMapper.createArrayNode();
        root.put("factualityScore", boundedScore(root.path("factualityScore").asDouble(0.6D)));
        ensureArray(root, "factualErrors", warnings);
        ensureArray(root, "hallucinationLog", warnings);
        if (!warnings.isEmpty()) {
            ArrayNode log = (ArrayNode) root.get("hallucinationLog");
            warnings.forEach(log::add);
        }
        return objectMapper.writeValueAsString(root);
    }

    private ObjectNode readObjectOrEmpty(String json) throws Exception {
        try {
            JsonNode node = objectMapper.readTree(json == null || json.isBlank() ? "{}" : json);
            return node != null && node.isObject() ? (ObjectNode) node.deepCopy() : objectMapper.createObjectNode();
        } catch (Exception e) {
            ObjectNode fallback = objectMapper.createObjectNode();
            fallback.put("verdict", "REVISE");
            fallback.put("critique", "Non-JSON critic output; schema repair fallback applied.");
            fallback.set("missingCitationIds", objectMapper.createArrayNode());
            fallback.set("factualErrors", objectMapper.createArrayNode());
            return fallback;
        }
    }

    private void ensureArray(ObjectNode root, String field, ArrayNode warnings) {
        if (!root.has(field) || !root.path(field).isArray()) {
            root.set(field, objectMapper.createArrayNode());
            warnings.add("field repaired as array: " + field);
        }
    }

    private double boundedScore(double score) {
        return Math.max(0D, Math.min(1D, score));
    }

    private boolean determineNeedsRevision(CriticReport report, FactualityResult factuality) {
        boolean verdictRequiresRevision = "REVISE".equalsIgnoreCase(report.verdict);
        boolean lowFactuality = factuality.isLow(orchestrationProps.getFactualityLowThreshold());
        boolean hasMissingCitations = !report.missingCitationIds.isEmpty();
        boolean hasFactualErrors = !report.factualErrors.isEmpty() || !factuality.factualErrors.isEmpty();

        return verdictRequiresRevision || lowFactuality || hasMissingCitations || hasFactualErrors;
    }

    private String buildReflectionReason(CriticReport report, FactualityResult factuality) {
        StringBuilder reason = new StringBuilder();

        if (!report.reflectionReason.isEmpty()) {
            reason.append(report.reflectionReason);
        } else if (factuality.isLow(orchestrationProps.getFactualityLowThreshold())) {
            reason.append(String.format("事实性得分过低 (%.2f < %.2f)，可能存在事实错误或幻觉内容。",
                    factuality.score, orchestrationProps.getFactualityLowThreshold()));
        } else if (!report.missingCitationIds.isEmpty()) {
            reason.append("缺少必要的引用证据：").append(String.join(", ", report.missingCitationIds));
        } else if (!report.factualErrors.isEmpty()) {
            reason.append("存在事实性错误：").append(String.join("; ", report.factualErrors));
        } else {
            reason.append(report.critique);
        }

        return reason.toString().trim();
    }

    private Map<String, Object> buildMetadata(CriticReport report, FactualityResult factuality) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("verdict", report.verdict);
        meta.put("critique", report.critique);
        meta.put("missingCitationIds", report.missingCitationIds);
        meta.put("factualErrors", report.factualErrors);
        meta.put("factualityScore", factuality.score);
        meta.put("hallucinationLog", factuality.hallucinationLog);
        return meta;
    }

    private CriticResult buildManualReviewResult(String content, int revisionRound, String reason) {
        return new CriticResult(
                "MANUAL_REVIEW_REQUIRED",
                "已达到返修轮次上限 (" + governanceProperties.getMaxRevisionRounds() + "轮)，建议人工复核",
                reason,
                List.of(),
                List.of(),
                0.0,
                List.of(),
                false,
                revisionRound,
                Map.of("requiresHumanReview", true, "revisionRound", revisionRound)
        );
    }

    private CriticResult buildFallbackResult(String content) {
        boolean hasCitation = content != null && content.contains("cite-");
        return new CriticResult(
                hasCitation ? "PASS" : "REVISE",
                hasCitation ? "LLM未配置，仅做基础引用检查通过" : "LLM未配置，内容缺少引用",
                "LLM 未配置，无法深度审查，仅做基础检查",
                List.of(),
                List.of(),
                0.5,
                List.of(),
                !hasCitation,
                0,
                Map.of("fallback", true)
        );
    }

    private ResourceReviewResult buildResourceFallbackResult(String topic) {
        ResourceReviewResult result = new ResourceReviewResult();
        result.verdict = "PASS";
        result.summary = "基础资源已生成，建议配置API后启用智能审查以获得详细质量评估。";
        result.coverageScore = 75;
        result.qualityScore = 80;
        result.difficultyMatchScore = 70;
        result.consistencyScore = 75;
        result.strengths.add("资源类型覆盖完整");
        result.strengths.add("讲义结构清晰");

        IssueInfo issue = new IssueInfo();
        issue.severity = "minor";
        issue.resourceType = "UNKNOWN";
        issue.description = "配置API后可获取详细审查";
        issue.suggestion = "建议配置DeepSeek API启用智能审查";
        result.issues.add(issue);

        result.needSupplement = false;
        result.priority = "low";
        result.fallback = true;

        return result;
    }

    // ==================== 内部数据类 ====================

    private static class CriticReport {
        String verdict = "PASS";
        String critique = "";
        String reflectionReason = "";
        List<String> missingCitationIds = new ArrayList<>();
        List<String> factualErrors = new ArrayList<>();
    }

    private static class FactualityResult {
        double score;
        List<String> factualErrors;
        List<String> hallucinationLog;

        FactualityResult(double score, List<String> factualErrors, List<String> hallucinationLog) {
            this.score = score;
            this.factualErrors = factualErrors;
            this.hallucinationLog = hallucinationLog;
        }

        boolean isLow(double threshold) {
            return score < threshold;
        }
    }

    // ==================== 公共结果类 ====================

    /**
     * 内容审查结果
     */
    public record CriticResult(
            String verdict,           // PASS, REVISE, MANUAL_REVIEW_REQUIRED
            String critique,          // 审查总结
            String reflectionReason,  // 返修原因/指导
            List<String> missingCitationIds,
            List<String> factualErrors,
            double factualityScore,
            List<String> hallucinationLog,
            boolean needsRevision,
            int nextRevisionRound,
            Map<String, Object> metadata
    ) {}

    /**
     * 资源集审查结果
     */
    public static class ResourceReviewResult {
        public String verdict;
        public String summary;
        public int coverageScore;
        public int qualityScore;
        public int difficultyMatchScore;
        public int consistencyScore;
        public List<String> strengths = new ArrayList<>();
        public List<IssueInfo> issues = new ArrayList<>();
        public boolean needSupplement;
        public List<String> supplementTypes = new ArrayList<>();
        public String priority;
        public boolean fallback;
    }

    /**
     * 问题信息
     */
    public static class IssueInfo {
        public String severity;
        public String resourceType;
        public String description;
        public String suggestion;
    }
}
