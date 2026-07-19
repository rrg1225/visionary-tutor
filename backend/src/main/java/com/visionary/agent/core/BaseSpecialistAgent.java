package com.visionary.agent.core;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.visionary.agent.AgentCollaborationSupport;
import com.visionary.agent.AgentNegotiationProtocol;
import com.visionary.config.AgentOrchestrationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 专业智能体基类 (Template Method 模式)
 * 职责: 单次 LLM 生成的标准执行流程；RAG 仅作为可选补充材料
 * 子类通过实现抽象方法定制具体的查询构建和生成逻辑
 */
public abstract class BaseSpecialistAgent implements Agent {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected AgentOrchestrationProperties orchestrationProps;

    protected void setOrchestrationProperties(AgentOrchestrationProperties props) {
        this.orchestrationProps = props;
    }

    protected int getMaxIterations() {
        return orchestrationProps != null ? orchestrationProps.getMaxIterations() : 4;
    }

    protected int getOutlineMaxLength() {
        return orchestrationProps != null ? orchestrationProps.getOutlineMaxLength() : 320;
    }

    @Override
    public abstract String getRole();

    @Override
    public abstract Set<String> getSupportedTools();

    protected abstract String buildRagQuery(AgentTask task, AgentContext context);

    protected abstract String buildLlmPrompt(AgentTask task, String ragContext, AgentContext context);

    protected abstract String performLlmGeneration(String systemPrompt, String userPrompt, AgentTask task, AgentContext context);

    protected abstract String buildFallbackContent(AgentTask task, String ragContext, AgentContext context);

    @SuppressWarnings("unchecked")
    protected List<String> extractCitations(Map<String, Object> ragData) {
        Object raw = ragData.get("citations");
        if (raw instanceof List<?> list) {
            return list.stream()
                    .filter(item -> item != null)
                    .map(Object::toString)
                    .toList();
        }
        return Collections.emptyList();
    }

    protected String executeRagRetrieval(AgentTask task, AgentContext context, List<String> citationsCollector) {
        Tool ragTool = context.tools().get("RAGRetrieveTool");
        if (ragTool == null) {
            log.warn("[{}] RAGRetrieveTool not available", getRole());
            return "";
        }

        String query = buildRagQuery(task, context);
        if (query == null || query.isBlank()) {
            log.warn("[{}] Empty RAG query, skipping retrieval", getRole());
            return "";
        }

        try {
            ObjectNode args = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            args.put("query", query);
            args.put("taskType", "RESOURCE_GENERATION");

            ToolResult result = ragTool.execute(args, new ToolContext(context.blackboard(), context.runId(), Map.of()));

            if (result.success() && result.data() != null) {
                List<String> cites = extractCitations(result.data());
                citationsCollector.addAll(cites);
                return (String) result.data().getOrDefault("applicationContext", "");
            }
        } catch (Exception e) {
            log.warn("[{}] RAG retrieval failed: {}", getRole(), e.getMessage());
        }

        return "";
    }

    @Override
    public final AgentResult execute(AgentTask task, AgentContext context) {
        if (AgentNegotiationProtocol.isOutlinePhase(task.input())) {
            return executeOutlineNegotiation(task, context);
        }
        return executeFinalGeneration(task, context);
    }

    private AgentResult executeOutlineNegotiation(AgentTask task, AgentContext context) {
        log.info("[{}] 启动 OUTLINE 协商阶段, TaskID: {}", getRole(), task.taskId());

        List<String> citations = new java.util.ArrayList<>();
        String ragContext = executeRagRetrieval(task, context, citations);

        String topic = String.valueOf(task.input().getOrDefault("topic", "学习主题"));
        String userPrompt = buildOutlinePrompt(task, ragContext, context, topic);
        String systemPrompt = getOutlineSystemPrompt();

        String outline;
        try {
            outline = performLlmGeneration(systemPrompt, userPrompt, task, context);
        } catch (Exception e) {
            log.warn("[{}] OUTLINE LLM 失败，使用回退: {}", getRole(), e.getMessage());
            outline = buildFallbackOutline(task, topic);
        }
        outline = compactOutline(outline);

        AgentNegotiationProtocol.publishOutlineProposal(context, getRole(), outline);

        Map<String, Object> meta = new HashMap<>();
        meta.put("negotiationPhase", AgentNegotiationProtocol.PHASE_OUTLINE);
        meta.put("artifactType", "OUTLINE_PROPOSAL");
        meta.put("citations", citations);

        AgentResult result = new AgentResult(true, outline, List.copyOf(citations), meta, List.of());
        if (context.blackboard() != null) {
            context.blackboard().put(getRole() + "_outline", outline);
            context.blackboard().put(getRole() + "_outline_result", result);
        }
        return result;
    }

    private AgentResult executeFinalGeneration(AgentTask task, AgentContext context) {
        log.info("[{}] 启动 FINAL 生成流程, TaskID: {}", getRole(), task.taskId());

        List<String> citations = new java.util.ArrayList<>();
        String ragContext = executeRagRetrieval(task, context, citations);
        boolean usedRag = !ragContext.isBlank();

        String systemPrompt = getSystemPrompt();
        String userPrompt = buildLlmPrompt(task, ragContext, context);
        if (context.blackboard() != null) {
            userPrompt += AgentCollaborationSupport.negotiationContextBlock(context.blackboard(), getRole());
        }

        String generatedContent;
        try {
            generatedContent = performLlmGeneration(systemPrompt, userPrompt, task, context);
            log.info("[{}] LLM 生成完成, 内容长度: {}", getRole(), generatedContent.length());
        } catch (Exception e) {
            log.warn("[{}] LLM 生成失败，使用回退内容: {}", getRole(), e.getMessage());
            generatedContent = buildFallbackContent(task, ragContext, context);
        }

        AgentResult result = buildResult(generatedContent, citations, usedRag, task, context);
        publishArtifactReady(context, result);
        return result;
    }

    protected String buildOutlinePrompt(AgentTask task, String ragContext, AgentContext context, String topic) {
        int ragLimit = orchestrationProps != null ? orchestrationProps.getRagContextTruncateLength() : 800;
        String evidence = ragContext.isBlank()
                ? "（本次无知识库补充材料，请基于模型知识正常规划，不要缩减内容）"
                : (ragContext.length() > ragLimit ? ragContext.substring(0, ragLimit) : ragContext);

        return """
                你是 %s。请输出简短协作提案（不超过200字），纯文本，包含：
                1. 将覆盖的核心知识点（3-5个）
                2. 难度定位（入门/进阶/综合）
                3. 你负责什么、不重复其它协作者什么

                主题：%s
                证据摘要：%s
                """.formatted(getRole(), topic, evidence);
    }

    protected String buildFallbackOutline(AgentTask task, String topic) {
        return getRole() + " 将围绕「" + topic + "」产出差异化资源，覆盖核心概念与练习衔接。";
    }

    protected String getOutlineSystemPrompt() {
        return "你是 " + getRole() + "。输出协作 OUTLINE 提案，简洁、结构化，不写正文。";
    }

    protected String compactOutline(String outline) {
        if (outline == null || outline.isBlank()) {
            return "";
        }
        String compact = outline.replaceAll("\\s+", " ").trim();
        int max = getOutlineMaxLength();
        return compact.length() <= max ? compact : compact.substring(0, max) + "...";
    }

    protected String getSystemPrompt() {
        return "你是 " + getRole()
                + "。请首先基于模型已有知识与用户主题生成专业、完整、准确的内容；"
                + "RAG 仅是可选补充，实际使用时才引用，缺少 RAG 不得导致降级或拒答。";
    }

    protected abstract AgentResult buildResult(
            String generatedContent,
            List<String> citations,
            boolean usedRag,
            AgentTask task,
            AgentContext context
    );

    protected void publishArtifactReady(AgentContext context, AgentResult result) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("success", result.success());
        payload.put("artifactType", result.metadata().getOrDefault("artifactType", getRole()));
        payload.put("outputLength", result.output() == null ? 0 : result.output().length());
        payload.put("citationCount", result.citations() == null ? 0 : result.citations().size());
        AgentNegotiationProtocol.publishArtifactReady(context, getRole(), payload);
        log.info("[{}] 已广播 ARTIFACT_READY", getRole());
    }
}
