package com.visionary.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.visionary.agent.core.AgentContext;
import com.visionary.agent.core.AgentResult;
import com.visionary.agent.core.AgentTask;
import com.visionary.agent.core.BaseSpecialistAgent;
import com.visionary.agent.core.SharedBlackboard;
import com.visionary.agent.core.Tool;
import com.visionary.agent.core.ToolContext;
import com.visionary.client.DeepSeekApiClient;
import com.visionary.config.AgentOrchestrationProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * MindMapAgent - generates structured mind map using standard RAG + LLM pipeline.
 */
@Service
public class MindMapAgent extends BaseSpecialistAgent {

    private static final String BB_CITATIONS = "MindMapAgent_citations";
    private static final String BB_USED_RAG = "MindMapAgent_usedRAG";
    private static final String BB_REVISION_APPLIED = "MindMapAgent_revisionApplied";
    private static final String BB_TASK_INPUT = "MindMapAgent_taskInput";
    private static final String BB_TOPIC = "MindMapAgent_topic";

    private final ObjectMapper objectMapper;
    private final DeepSeekApiClient deepSeekApiClient;

    public MindMapAgent(ObjectMapper objectMapper, DeepSeekApiClient deepSeekApiClient,
                        AgentOrchestrationProperties orchestrationProps) {
        this.objectMapper = objectMapper;
        this.deepSeekApiClient = deepSeekApiClient;
        setOrchestrationProperties(orchestrationProps);
    }

    @Override
    public String getRole() {
        return "MindMapAgent";
    }

    @Override
    public Set<String> getSupportedTools() {
        return Set.of("RAGRetrieveTool", "ArtifactPersistTool");
    }

    @Override
    protected String getSystemPrompt() {
        return """
                你是MindMapAgent，专门生成准确、完整的 Mermaid 知识导图。

                严格规则：
                1. 以模型已有知识和用户主题构建完整结构；RAG 仅作可选补充，实际采用时才保留 citationId
                2. 使用Mermaid mindmap语法（注意：是mindmap不是graph）
                3. 层级清晰：根节点 → 主分支 → 子分支 → 叶子节点
                4. 每个主分支附带1-2句学习说明
                5. 节点命名简洁（<10字），使用中文
                """;
    }

    @Override
    protected String buildRagQuery(AgentTask task, AgentContext context) {
        SharedBlackboard blackboard = context.blackboard();
        String topic = resolveTopic(task, blackboard);
        String revisionInstruction = Optional.ofNullable(task.input().get("revisionInstruction"))
                .map(Object::toString)
                .orElse("");

        blackboard.put(BB_TASK_INPUT, task.input());
        blackboard.put(BB_TOPIC, topic);
        blackboard.put(BB_REVISION_APPLIED, !revisionInstruction.isBlank());

        return (topic + " 思维导图 " + revisionInstruction).trim();
    }

    @Override
    protected String buildLlmPrompt(AgentTask task, String ragContext, AgentContext context) {
        SharedBlackboard blackboard = context.blackboard();
        String topic = Optional.ofNullable(blackboard.get(BB_TOPIC))
                .map(Object::toString)
                .orElse("知识图谱");

        String detailLevel = String.valueOf(task.input().getOrDefault("detailLevel", "EXPANDED"));

        String profile = context.blackboard().getLearnerProfileSnapshot();
        if (profile == null || profile.isBlank()) {
            profile = String.valueOf(task.input().getOrDefault(
                    "learnerProfileSnapshot",
                    task.input().getOrDefault("learnerProfile", "{}")
            ));
        }

        String evidence = ragContext.isBlank()
                ? "本次没有知识库补充材料，请基于模型知识正常生成，且不要编造引用"
                : ragContext;
        String revisionBlock = AgentPromptSupport.revisionBlock(task);

        int profileTruncateLength = orchestrationProps != null ?
                orchestrationProps.getMindMapAgentProfileTruncateLength() : 300;

        return """
                生成"%s"的知识结构思维导图

                细节级别：%s
                - CORE: 3-4个主分支
                - EXPANDED: 5-6个主分支，部分有子分支
                - COMPREHENSIVE: 完整知识体系，多级分支

                学生画像：%s

                可选知识库补充材料：
                %s

                请输出完整 Markdown，包含 Mermaid 代码和学习说明。没有补充材料时也要正常完成全部层级，不要输出“推测结构”或“证据不足”占位。
                """.formatted(
                topic,
                detailLevel,
                profile.length() > profileTruncateLength ? profile.substring(0, profileTruncateLength) : profile,
                evidence
        ) + revisionBlock;
    }

    @Override
    protected String performLlmGeneration(String systemPrompt, String userPrompt, AgentTask task, AgentContext context) {
        if (deepSeekApiClient == null || !deepSeekApiClient.isConfigured()) {
            throw new IllegalStateException("DeepSeek API not configured");
        }
        try {
            return deepSeekApiClient.chat(systemPrompt, userPrompt, false);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected String buildFallbackContent(AgentTask task, String ragContext, AgentContext context) {
        SharedBlackboard blackboard = context.blackboard();
        String topic = Optional.ofNullable(blackboard.get(BB_TOPIC))
                .map(Object::toString)
                .orElse("知识图谱");

        return "# " + topic + " 思维导图\n\n"
                + "```mermaid\n"
                + "mindmap\n  root((" + topic + "))\n    "
                + (ragContext.isBlank() ? "概念\n    子概念" : ragContext.substring(0, Math.min(400, ragContext.length())))
                + "\n```\n\n（证据来源已标注）";
    }

    @Override
    protected AgentResult buildResult(String generatedContent, List<String> citations, boolean usedRag,
                                      AgentTask task, AgentContext context) {
        SharedBlackboard blackboard = context.blackboard();
        String topic = Optional.ofNullable(blackboard.get(BB_TOPIC))
                .map(Object::toString)
                .orElse("知识图谱");
        boolean revisionApplied = Boolean.TRUE.equals(blackboard.get(BB_REVISION_APPLIED));

        @SuppressWarnings("unchecked")
        Map<String, Object> taskInput = blackboard.get(BB_TASK_INPUT) instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();

        persistArtifact(taskInput, context, topic, generatedContent);

        blackboard.put(BB_CITATIONS, new ArrayList<>(citations));
        blackboard.put(BB_USED_RAG, usedRag);

        blackboard.addTrace(new SharedBlackboard.AgentRunTrace(
                getRole(),
                "rag-llm-pipeline",
                "MindMap for " + topic + (revisionApplied ? " revised from critic feedback" : ""),
                Instant.now()
        ));

        return new AgentResult(
                true,
                generatedContent,
                citations,
                Map.of(
                        "artifactType", "MINDMAP",
                        "usedRAG", usedRag,
                        "agentLoop", "standard-pipeline",
                        "revisionApplied", revisionApplied
                ),
                List.of()
        );
    }

    private String resolveTopic(AgentTask task, SharedBlackboard blackboard) {
        if (blackboard.getCurrentTopic() != null && !blackboard.getCurrentTopic().isBlank()) {
            return blackboard.getCurrentTopic();
        }
        Object topic = task.input().get("topic");
        if (topic != null && !topic.toString().isBlank()) {
            return topic.toString();
        }
        return "知识图谱";
    }

    private void persistArtifact(
            Map<String, Object> taskInput,
            AgentContext context,
            String topic,
            String content
    ) {
        Tool persistTool = context.tools().get("ArtifactPersistTool");
        if (persistTool == null || !taskInput.containsKey("learningSessionId")) {
            return;
        }

        Object sessionRaw = taskInput.get("learningSessionId");
        Long learningSessionId = sessionRaw instanceof Number number
                ? number.longValue()
                : Long.parseLong(sessionRaw.toString());

        ObjectNode args = objectMapper.createObjectNode();
        args.put("learningSessionId", learningSessionId);
        args.put("type", "MINDMAP");
        args.put("title", topic + " 思维导图");
        args.put("content", content);
        persistTool.execute(args, new ToolContext(context.blackboard(), context.runId(), Map.of()));
    }
}
