package com.visionary.resourcegeneration.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.client.DeepSeekApiClient;
import com.visionary.dto.ResourceGenerationRequest;
import com.visionary.entity.GeneratedArtifact.ArtifactType;
import com.visionary.rag.CitationValidator;
import com.visionary.rag.RagRetrievalResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
public class CriticReviewService {

    private static final double FACTUALITY_THRESHOLD = 0.75;

    private final DeepSeekApiClient deepSeekApiClient;
    private final ObjectMapper objectMapper;

    public CriticReviewService(DeepSeekApiClient deepSeekApiClient, ObjectMapper objectMapper) {
        this.deepSeekApiClient = deepSeekApiClient;
        this.objectMapper = objectMapper;
    }

    public CriticReviewDecision critique(
            ArtifactType type,
            String topic,
            String content,
            ResourceGenerationRequest request,
            CitationValidator.ValidationResult validation,
            RagRetrievalResult rag
    ) {
        boolean citationRisk = requiresCitationRevision(validation);
        if (!deepSeekApiClient.isConfigured()) {
            return new CriticReviewDecision(citationRisk, validationMessage(validation));
        }

        FactualityAssessment factuality = assessFactuality(topic, content, rag);
        try {
            String raw = deepSeekApiClient.chat(
                    "你是 CriticAgent。只输出 JSON，不要输出 Markdown。",
                    critiquePrompt(type, topic, content, request, validation, rag, factuality),
                    false
            );
            JsonNode root = objectMapper.readTree(extractJsonObject(raw));
            String verdict = root.path("verdict").asText("PASS");
            String message = root.path("critique").asText(validationMessage(validation));
            boolean lowFactuality = factuality.assessed()
                    && factuality.score() < FACTUALITY_THRESHOLD;
            boolean needsRevision = citationRisk
                    || "REVISE".equalsIgnoreCase(verdict)
                    || lowFactuality
                    || !factuality.errors().isEmpty();
            if (lowFactuality) {
                message += " | Factuality=" + factuality.score() + " 触发重规划";
            }
            String report = "CriticReport{factualityScore=%.2f, assessed=%s, factualErrors=%d, "
                    + "hallucinationLog=%d, citation=%s, verdict=%s}";
            report = report.formatted(
                    factuality.score(),
                    factuality.assessed(),
                    factuality.errors().size(),
                    factuality.hallucinations().size(),
                    validationStatus(validation),
                    verdict
            );
            return new CriticReviewDecision(needsRevision, message + " | " + report);
        } catch (Exception exception) {
            log.warn("CriticAgent failed, keeping the independent citation verdict: {}", exception.getMessage());
            return new CriticReviewDecision(citationRisk, validationMessage(validation));
        }
    }

    public String repair(
            ArtifactType type,
            String topic,
            String previousContent,
            String critique,
            String revisedPlan,
            RagRetrievalResult rag
    ) {
        String systemPrompt = "你是 CriticAgent 指派的返修智能体。修正事实、引用和完整性问题，保留可用的原有结构。";
        try {
            return deepSeekApiClient.chat(
                    systemPrompt,
                    """
                            主题：%s
                            资源类型：%s
                            PlannerAgent 重规划：%s
                            审查问题：%s
                            原始内容：%s
                            可选知识库材料：%s

                            请重写资源。模型已有知识是正常生成基础；如实际采用知识库材料，只能使用允许列表中的 citationId。
                            知识库没有材料时照常完成内容，citations 留空，不要在学习正文中反复提示“证据不足”。
                            """.formatted(
                            topic,
                            type.name(),
                            revisedPlan,
                            critique,
                            previousContent,
                            safeRag(rag).toCitationInstructionBlock()
                    ),
                    false
            );
        } catch (Exception exception) {
            log.warn("{} repair failed, keeping original content: {}", type, exception.getMessage());
            return previousContent;
        }
    }

    public String replan(
            String topic,
            ArtifactType type,
            String agentName,
            String originalPlan,
            List<String> blackboardCritiques,
            CriticReviewDecision critique,
            ResourceGenerationRequest request,
            RagRetrievalResult rag
    ) {
        String deterministicPlan = originalPlan + "\n\n[CriticAgent 返修约束] " + critique.message();
        if (!deepSeekApiClient.isConfigured()) {
            return deterministicPlan;
        }
        try {
            return deepSeekApiClient.chat(
                    "你是 PlannerAgent。根据 CriticAgent 结果更新任务计划，只输出可执行的重规划清单。",
                    """
                            学习主题：%s
                            当前返修资源：%s / %s
                            原计划：%s
                            黑板批注：%s
                            CriticAgent 意见：%s
                            学生画像：%s
                            可选知识库材料：%s

                            明确必须重写、可保留内容和需要核对的事实。知识库只是补充材料，缺少材料不应缩减正常教学内容。
                            """.formatted(
                            topic,
                            type.name(),
                            agentName,
                            originalPlan,
                            String.join("\n", blackboardCritiques),
                            critique.message(),
                            firstNonBlank(request.learnerProfileSnapshot(), "暂无"),
                            safeRag(rag).toCitationInstructionBlock()
                    ),
                    false
            );
        } catch (Exception exception) {
            log.warn("PlannerAgent replan failed, using deterministic replan: {}", exception.getMessage());
            return deterministicPlan;
        }
    }

    private FactualityAssessment assessFactuality(String topic, String content, RagRetrievalResult rag) {
        try {
            String raw = deepSeekApiClient.chat(
                    "你是 FactualityVerifier，独立检查内容事实性。只输出 JSON。知识库材料是可选参考，不是唯一知识来源。",
                    """
                            主题：%s
                            待检查内容：%s
                            可选知识库材料：%s
                            请同时使用你的可靠知识判断概念、公式、代码与因果关系是否正确。
                            输出 JSON：{"factualityScore":0.0,"factualErrors":[],"hallucinationLog":[]}
                            """.formatted(topic, truncate(content, 2000), safeRag(rag).toCitationInstructionBlock()),
                    false
            );
            JsonNode root = objectMapper.readTree(extractJsonObject(raw));
            List<String> errors = jsonArray(root, "factualErrors");
            List<String> hallucinations = jsonArray(root, "hallucinationLog");
            return new FactualityAssessment(
                    root.path("factualityScore").asDouble(0.8),
                    errors,
                    hallucinations,
                    true
            );
        } catch (Exception exception) {
            // An unavailable secondary verifier is an observability issue, not
            // evidence that the primary model output itself is low quality.
            log.warn("FactualityVerifier unavailable: {}", exception.getMessage());
            return new FactualityAssessment(
                    0.8,
                    List.of(),
                    List.of("FactualityVerifier 暂不可用，未据此降低内容等级"),
                    false
            );
        }
    }

    private String critiquePrompt(
            ArtifactType type,
            String topic,
            String content,
            ResourceGenerationRequest request,
            CitationValidator.ValidationResult validation,
            RagRetrievalResult rag,
            FactualityAssessment factuality
    ) {
        return """
                主题：%s
                资源类型：%s
                学生画像：%s
                薄弱点：%s
                引用校验：%s
                可选知识库材料：%s
                FactualityScore：%.2f
                事实错误：%s
                待审查内容：%s

                审查内容是否正确、完整、可学习、符合资源格式。没有知识库材料本身不是缺陷；只有虚构引用、错误引用、事实错误或内容缺失才要求返修。
                输出 JSON：{"verdict":"PASS 或 REVISE","critique":"审查意见"}
                """.formatted(
                topic,
                type.name(),
                firstNonBlank(request.learnerProfileSnapshot(), "暂无"),
                firstNonBlank(request.weakPointsSnapshot(), "暂无"),
                validationMessage(validation),
                safeRag(rag).toCitationInstructionBlock(),
                factuality.score(),
                factuality.errors().isEmpty() ? "无" : String.join(";", factuality.errors()),
                truncate(content, 2500)
        );
    }

    private static boolean requiresCitationRevision(CitationValidator.ValidationResult validation) {
        String status = validationStatus(validation).toUpperCase(Locale.ROOT);
        return "INVALID_CITATION".equals(status) || "WEAK_GROUNDING".equals(status);
    }

    private static String validationStatus(CitationValidator.ValidationResult validation) {
        return validation == null || validation.status() == null ? "UNVERIFIED" : validation.status();
    }

    private static String validationMessage(CitationValidator.ValidationResult validation) {
        return validation == null || validation.message() == null ? "引用校验结果不可用" : validation.message();
    }

    private static RagRetrievalResult safeRag(RagRetrievalResult rag) {
        return rag == null ? RagRetrievalResult.empty() : rag;
    }

    private static List<String> jsonArray(JsonNode root, String field) {
        List<String> values = new ArrayList<>();
        root.path(field).forEach(node -> values.add(node.asText()));
        return List.copyOf(values);
    }

    private static String extractJsonObject(String raw) {
        if (raw == null) {
            return "{}";
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return start >= 0 && end > start ? raw.substring(start, end + 1) : "{}";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "...";
    }

    private record FactualityAssessment(
            double score,
            List<String> errors,
            List<String> hallucinations,
            boolean assessed
    ) {
    }
}
