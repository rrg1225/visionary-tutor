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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Generates a grounded lecture handout using standard RAG + LLM pipeline.
 */
@Service
public class DocAgent extends BaseSpecialistAgent {

    private static final String BB_CITATIONS = "DocAgent_citations";
    private static final String BB_USED_RAG = "DocAgent_usedRAG";
    private static final String BB_REVISION_APPLIED = "DocAgent_revisionApplied";
    private static final String BB_TASK_INPUT = "DocAgent_taskInput";
    private static final String BB_TOPIC = "DocAgent_topic";

    private final ObjectMapper objectMapper;
    private final DeepSeekApiClient deepSeekApiClient;

    public DocAgent(ObjectMapper objectMapper, DeepSeekApiClient deepSeekApiClient,
                    AgentOrchestrationProperties orchestrationProps) {
        this.objectMapper = objectMapper;
        this.deepSeekApiClient = deepSeekApiClient;
        setOrchestrationProperties(orchestrationProps);
    }

    @Override
    public String getRole() {
        return "DocAgent";
    }

    @Override
    public Set<String> getSupportedTools() {
        return Set.of("RAGRetrieveTool", "ArtifactPersistTool");
    }

    @Override
    protected String getSystemPrompt() {
        return """
                你是DocAgent，专门生成准确、完整的计算机视觉/深度学习课程讲义。

                严格规则：
                1. 以模型已有知识和用户主题完成讲义；RAG 仅作可选补充，实际采用时才保留 citationId
                2. 讲义结构：学习目标 → 核心概念 → 公式推导 → 代码示例 → 练习题
                3. 使用Markdown格式，代码块标注语言类型
                4. 复杂概念配合数学公式（LaTeX格式）
                5. 每节末尾给出小结和自测问题

                分课教学规范（借鉴渐进式教学流）：
                - 若 blackboard 标记 chapterZero=true，生成「第0章 教学说明与进度」：
                  含用户原始指令、执行要点、教学进度表、可调大纲
                - 常规讲义按「序号_课程标题」命名感组织（如 01_入门.md）
                - 每课末尾追加「📝 学习记录」：用户问题、疑惑点、掌握情况（无则写「无」）
                - 每次只推进一个课程文件的内容，不一次性输出整本教材
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

        return (topic + " " + revisionInstruction).trim();
    }

    @Override
    protected String buildLlmPrompt(AgentTask task, String ragContext, AgentContext context) {
        SharedBlackboard blackboard = context.blackboard();
        String topic = Optional.ofNullable(blackboard.get(BB_TOPIC))
                .map(Object::toString)
                .orElse("课程主题");

        Object depth = task.input().get("depth");
        String depthLevel = depth != null ? depth.toString() : "MEDIUM";
        String focusConcepts = String.valueOf(task.input().getOrDefault("focusConcepts", "[]"));

        String profile = blackboard.getLearnerProfileSnapshot();
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
                orchestrationProps.getDocAgentProfileTruncateLength() : 500;

        return """
                生成关于"%s"的系统讲义

                深度级别：%s（SURFACE=概述，MEDIUM=深入，DEEP=精通）
                学生画像：%s
                重点概念：%s

                证据材料：
                %s

                请输出完整Markdown讲义。
                """.formatted(
                topic,
                depthLevel,
                profile.length() > profileTruncateLength ? profile.substring(0, profileTruncateLength) : profile,
                focusConcepts,
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
                .orElse("课程主题");

        return "【讲义】" + topic + "\n\n"
                + (ragContext.isBlank()
                ? "（知识库证据不足，建议配置 RAG 后重新生成。）"
                : "基于检索证据生成的讲义预览：\n" + ragContext.substring(0, Math.min(800, ragContext.length())));
    }

    @Override
    protected AgentResult buildResult(String generatedContent, List<String> citations, boolean usedRag,
                                      AgentTask task, AgentContext context) {
        SharedBlackboard blackboard = context.blackboard();
        String topic = Optional.ofNullable(blackboard.get(BB_TOPIC))
                .map(Object::toString)
                .orElse("课程主题");
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
                "Handout for " + topic + (revisionApplied ? " revised from critic feedback" : ""),
                Instant.now()
        ));

        return new AgentResult(
                true,
                generatedContent,
                citations,
                Map.of(
                        "artifactType", "HANDOUT",
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
        return "课程主题";
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
        args.put("type", "HANDOUT");
        args.put("title", topic + " 讲义");
        args.put("content", content);
        persistTool.execute(args, new ToolContext(context.blackboard(), context.runId(), Map.of()));
    }
}
