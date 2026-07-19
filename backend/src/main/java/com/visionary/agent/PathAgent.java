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
 * PathAgent - generates personalized learning path using standard RAG + LLM pipeline.
 */
@Service
public class PathAgent extends BaseSpecialistAgent {

    private static final String BB_USED_RAG = "PathAgent_usedRAG";
    private static final String BB_TASK_INPUT = "PathAgent_taskInput";
    private static final String BB_TOPIC = "PathAgent_topic";

    private final ObjectMapper objectMapper;
    private final DeepSeekApiClient deepSeekApiClient;

    public PathAgent(ObjectMapper objectMapper, DeepSeekApiClient deepSeekApiClient,
                     AgentOrchestrationProperties orchestrationProps) {
        this.objectMapper = objectMapper;
        this.deepSeekApiClient = deepSeekApiClient;
        setOrchestrationProperties(orchestrationProps);
    }

    @Override
    public String getRole() {
        return "PathAgent";
    }

    @Override
    public Set<String> getSupportedTools() {
        return Set.of("RAGRetrieveTool", "ArtifactPersistTool");
    }

    @Override
    protected String getSystemPrompt() {
        return """
                你是PathAgent，专门为学生规划个性化学习路径。

                【极其重要的输出格式约束】
                你必须输出纯净的JSON字符串，严禁输出任何Markdown格式（如 ```json 代码块标记、# 标题、- 列表符号、**粗体**等）。
                输出必须是可直接被JSON解析器解析的纯JSON文本。

                严格规则：
                1. 基于已有资源安排学习顺序，不要规划未生成的资源类型
                2. 考虑学生画像调整路径难度和节奏
                3. 每个步骤明确：学习目标 → 使用资源 → 预计时间 → 自测方式
                4. 为薄弱点预留额外复习时间

                【输出JSON结构要求】
                必须包含以下两个顶级字段：
                {
                  "nodes": [
                    {
                      "id": "n1",
                      "topic": "步骤1主题名称",
                      "label": "步骤描述",
                      "resourceType": "推荐资源类型",
                      "estimatedMinutes": 30,
                      "rationale": "为什么安排这一步"
                    }
                  ],
                  "edges": [
                    {
                      "source": "n1",
                      "target": "n2",
                      "relation": "PREREQUISITE",
                      "label": "前置依赖"
                    }
                  ]
                }

                nodes数组：每个节点必须有id（字符串，如n1, n2...）、topic（主题）、label（描述）。
                edges数组：每个边必须有source（源节点id）、target（目标节点id），表示前置依赖关系（source必须在target之前学习）。
                输出必须是合法的、紧凑的JSON字符串，不要包含任何换行符或缩进以外的空白字符。
                """;
    }

    @Override
    protected String buildRagQuery(AgentTask task, AgentContext context) {
        SharedBlackboard blackboard = context.blackboard();
        String topic = resolveTopic(task, blackboard);

        blackboard.put(BB_TASK_INPUT, task.input());
        blackboard.put(BB_TOPIC, topic);

        return topic + " 学习路径规划";
    }

    @Override
    protected String buildLlmPrompt(AgentTask task, String ragContext, AgentContext context) {
        SharedBlackboard blackboard = context.blackboard();
        String topic = Optional.ofNullable(blackboard.get(BB_TOPIC))
                .map(Object::toString)
                .orElse("学习路径");

        String resources = String.valueOf(task.input().getOrDefault("availableResources", "[]"));
        String duration = String.valueOf(task.input().getOrDefault("targetDuration", "1周"));
        String revisionBlock = AgentPromptSupport.revisionBlock(task);

        String profile = context.blackboard().getLearnerProfileSnapshot();
        if (profile == null || profile.isBlank()) {
            profile = String.valueOf(task.input().getOrDefault(
                    "learnerProfileSnapshot",
                    task.input().getOrDefault("learnerProfile", "{}")
            ));
        }

        String evidence = ragContext.isBlank() ? "无" : ragContext;

        int profileTruncateLength = orchestrationProps != null ?
                orchestrationProps.getPathAgentProfileTruncateLength() : 400;

        return """
                为"%s"规划学习路径

                已有资源类型：%s
                学生画像：%s
                目标学习周期：%s
                薄弱点：%s

                参考资料：
                %s

                【极其重要的格式要求】
                你必须只输出纯JSON格式，严禁使用Markdown代码块(```json)包裹。
                输出必须是可以直接被JSON解析器解析的纯文本，包含nodes数组和edges数组。
                nodes数组中每个节点包含：id, topic, label, resourceType, estimatedMinutes, rationale字段。
                edges数组中每个边包含：source(源节点id), target(目标节点id), relation, label字段。
                不要输出任何Markdown格式、标题符号或解释性文字。

                请输出完整学习路径规划（5-7步，每步含时间、标准、资源），以纯净JSON格式返回。
                """.formatted(
                topic,
                resources,
                profile.length() > profileTruncateLength ? profile.substring(0, profileTruncateLength) : profile,
                duration,
                task.input().getOrDefault("weakPointsSnapshot", "待观察"),
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
                .orElse("学习路径");

        String resources = String.valueOf(task.input().getOrDefault("availableResources", "讲义、题库、导图"));
        String duration = String.valueOf(task.input().getOrDefault("targetDuration", "2周"));

        // 降级输出也必须符合JSON格式要求
        StringBuilder sb = new StringBuilder();
        sb.append("{\"nodes\":[");

        if (!ragContext.isBlank()) {
            // 基于RAG上下文生成简单的3步路径
            sb.append("{\"id\":\"n1\",\"topic\":\"概念理解\",\"label\":\"理解").append(escapeJson(topic))
                    .append("基础概念\",\"resourceType\":\"讲义\",\"estimatedMinutes\":30,\"rationale\":\"打好基础\"},")
                    .append("{\"id\":\"n2\",\"topic\":\"实践练习\",\"label\":\"完成基础练习题\",\"resourceType\":\"题库\",\"estimatedMinutes\":45,\"rationale\":\"巩固知识\"},")
                    .append("{\"id\":\"n3\",\"topic\":\"综合应用\",\"label\":\"项目实战\",\"resourceType\":\"导图\",\"estimatedMinutes\":60,\"rationale\":\"融会贯通\"}");
            sb.append("],\"edges\":[");
            sb.append("{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\",\"relation\":\"PREREQUISITE\",\"label\":\"前置依赖\"},")
                    .append("{\"id\":\"e2\",\"source\":\"n2\",\"target\":\"n3\",\"relation\":\"PREREQUISITE\",\"label\":\"前置依赖\"}");
        } else {
            // 最简化的降级路径
            sb.append("{\"id\":\"n1\",\"topic\":\"基础学习\",\"label\":\"学习").append(escapeJson(topic))
                    .append("基础\",\"resourceType\":\"讲义\",\"estimatedMinutes\":30,\"rationale\":\"入门\"},")
                    .append("{\"id\":\"n2\",\"topic\":\"练习巩固\",\"label\":\"完成练习\",\"resourceType\":\"题库\",\"estimatedMinutes\":45,\"rationale\":\"巩固\"},")
                    .append("{\"id\":\"n3\",\"topic\":\"综合实践\",\"label\":\"项目实践\",\"resourceType\":\"导图\",\"estimatedMinutes\":60,\"rationale\":\"应用\"}");
            sb.append("],\"edges\":[");
            sb.append("{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\",\"relation\":\"PREREQUISITE\",\"label\":\"learn-before\"},")
                    .append("{\"id\":\"e2\",\"source\":\"n2\",\"target\":\"n3\",\"relation\":\"PREREQUISITE\",\"label\":\"learn-before\"}");
        }

        sb.append("]}");
        return sb.toString();
    }

    /**
     * JSON 字符串转义辅助方法
     */
    private String escapeJson(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    @Override
    protected AgentResult buildResult(String generatedContent, List<String> citations, boolean usedRag,
                                      AgentTask task, AgentContext context) {
        SharedBlackboard blackboard = context.blackboard();
        String topic = Optional.ofNullable(blackboard.get(BB_TOPIC))
                .map(Object::toString)
                .orElse("学习路径");

        @SuppressWarnings("unchecked")
        Map<String, Object> taskInput = blackboard.get(BB_TASK_INPUT) instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();

        persistArtifact(taskInput, context, topic, generatedContent);

        blackboard.put(BB_USED_RAG, usedRag);

        blackboard.addTrace(new SharedBlackboard.AgentRunTrace(
                getRole(),
                "rag-llm-pipeline",
                "Path for " + topic,
                Instant.now()
        ));

        return new AgentResult(
                true,
                generatedContent,
                citations,
                Map.of(
                        "artifactType", "LEARNING_PATH",
                        "usedRAG", usedRag,
                        "agentLoop", "standard-pipeline",
                        "consumedPeerRoles", AgentCollaborationSupport.consumedPeerRoles(blackboard, getRole())
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
        return "学习路径";
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
        args.put("type", "LEARNING_PATH");
        args.put("title", topic + " 学习路径");
        args.put("content", content);
        persistTool.execute(args, new ToolContext(context.blackboard(), context.runId(), Map.of()));
    }
}
