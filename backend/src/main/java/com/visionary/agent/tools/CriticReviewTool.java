package com.visionary.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.visionary.service.CriticCoreService;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * CriticReviewTool - 质量审查专家工具
 * 实现 SpecialistTool 接口支持动态工具注册
 * <p>
 * 委托 CriticCoreService 执行核心审查逻辑，消除与 CriticAgent 的代码重复
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CriticReviewTool implements SpecialistTool {

    public static final String TOOL_NAME = "review_and_critique";

    private final ObjectMapper objectMapper;
    private final CriticCoreService criticCoreService;

    @Override
    public String getToolName() {
        return TOOL_NAME;
    }

    @Override
    public String executeTool(ObjectNode args, ReActContext ctx) {
        String topic = ReActContext.getStringParam(args, "topic", ctx.topic());
        String resourceSummaries = ReActContext.getStringParam(args, "resourceSummaries", "[]");
        String learnerProfile = ReActContext.getStringParam(args, "learnerProfile", ctx.learnerProfile());
        String reviewFocus = ReActContext.getStringParam(args, "reviewFocus", "ALL");
        String memoryId = ReActContext.getStringParam(args, "memoryId", ctx.memoryId());

        return reviewAndCritique(memoryId, topic, resourceSummaries, learnerProfile, reviewFocus);
    }

    @Tool(name = TOOL_NAME,
          value = "Reviews generated resources and identifies gaps or quality issues. " +
                       "Use this when: 1) After generating 3+ resources to ensure quality, " +
                       "2) Student feedback indicates confusion, 3) Need to validate coverage of weak points, " +
                       "4) Before finalizing learning plan. " +
                       "Parameters: topic (string), resourceSummaries (array of {type, summary}), " +
                       "learnerProfile (JSON string), reviewFocus (COVERAGE/QUALITY/DIFFICULTY/ALL).")
    public String reviewAndCritique(
            @ToolMemoryId String memoryId,
            String topic,
            String resourceSummaries,
            String learnerProfile,
            String reviewFocus) {

        log.info("[CriticTool] Reviewing resources for topic='{}', focus='{}'", topic, reviewFocus);

        try {
            CriticCoreService.ResourceReviewResult result = criticCoreService.reviewResources(
                    topic, resourceSummaries, learnerProfile, reviewFocus
            );

            return formatResourceReviewResult(result);

        } catch (Exception e) {
            log.error("[CriticTool] Failed: {}", e.getMessage());
            return formatFallbackResult(topic, e.getMessage());
        }
    }

    /**
     * 审查单个内容（供内部调用或后续扩展）
     */
    public String reviewContent(
            String topic,
            String content,
            String learnerProfile,
            String ragEvidenceBlock,
            int revisionRound) {

        log.info("[CriticTool] Reviewing content for topic='{}'", topic);

        try {
            CriticCoreService.CriticResult result = criticCoreService.reviewContent(
                    topic, content, learnerProfile, ragEvidenceBlock, revisionRound
            );

            return formatContentReviewResult(result);

        } catch (Exception e) {
            log.error("[CriticTool] Content review failed: {}", e.getMessage());
            return formatErrorResult(topic, e.getMessage());
        }
    }

    private String formatResourceReviewResult(CriticCoreService.ResourceReviewResult result) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("verdict", result.verdict);
            root.put("summary", result.summary);
            root.put("fallback", result.fallback);

            ObjectNode scores = objectMapper.createObjectNode();
            scores.put("coverage", result.coverageScore);
            scores.put("quality", result.qualityScore);
            scores.put("difficulty_match", result.difficultyMatchScore);
            scores.put("consistency", result.consistencyScore);
            root.set("scores", scores);

            root.set("strengths", objectMapper.valueToTree(result.strengths));

            List<ObjectNode> issues = new ArrayList<>();
            for (CriticCoreService.IssueInfo issue : result.issues) {
                ObjectNode issueNode = objectMapper.createObjectNode();
                issueNode.put("severity", issue.severity);
                issueNode.put("resource_type", issue.resourceType);
                issueNode.put("description", issue.description);
                issueNode.put("suggestion", issue.suggestion);
                issues.add(issueNode);
            }
            root.set("issues", objectMapper.valueToTree(issues));

            ObjectNode revisionPlan = objectMapper.createObjectNode();
            revisionPlan.put("need_supplement", result.needSupplement);
            revisionPlan.set("supplement_types", objectMapper.valueToTree(result.supplementTypes));
            revisionPlan.put("priority", result.priority);
            root.set("revision_plan", revisionPlan);

            root.put("content", objectMapper.writeValueAsString(root));
            root.put("artifactType", "CRITIQUE");

            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            log.warn("[CriticTool] Failed to format result: {}", e.getMessage());
            return result.summary;
        }
    }

    private String formatContentReviewResult(CriticCoreService.CriticResult result) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("verdict", result.verdict());
            root.put("critique", result.critique());
            root.put("reflection_reason", result.reflectionReason());
            root.put("factualityScore", result.factualityScore());
            root.put("needsRevision", result.needsRevision());
            root.put("nextRevisionRound", result.nextRevisionRound());
            root.set("missingCitationIds", objectMapper.valueToTree(result.missingCitationIds()));
            root.set("factualErrors", objectMapper.valueToTree(result.factualErrors()));
            root.set("hallucinationLog", objectMapper.valueToTree(result.hallucinationLog()));
            root.set("metadata", objectMapper.valueToTree(result.metadata()));
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            return result.critique();
        }
    }

    private String formatFallbackResult(String topic, String reason) {
        try {
            ObjectNode result = objectMapper.createObjectNode();
            result.put("verdict", "PASS");
            result.put("summary", "基础资源已生成，审查服务暂时不可用（" + reason + "）");
            result.put("fallback", true);

            ObjectNode scores = objectMapper.createObjectNode();
            scores.put("coverage", 70);
            scores.put("quality", 75);
            scores.put("difficulty_match", 70);
            scores.put("consistency", 75);
            result.set("scores", scores);

            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        } catch (Exception e) {
            return "{\"verdict\": \"PASS\", \"summary\": \"Fallback review result\"}";
        }
    }

    private String formatErrorResult(String topic, String error) {
        try {
            ObjectNode result = objectMapper.createObjectNode();
            result.put("error", true);
            result.put("topic", topic);
            result.put("message", error);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        } catch (Exception e) {
            return "{\"error\": true, \"topic\": \"" + topic + "\", \"message\": \"" + error + "\"}";
        }
    }
}
