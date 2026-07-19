package com.visionary.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.visionary.agent.core.AgentContext;
import com.visionary.agent.core.AgentHandoff;
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
 * ReadingAgent generates an evidence-grounded extended reading packet using standard RAG + LLM pipeline.
 */
@Service
public class ReadingAgent extends BaseSpecialistAgent {

    private static final String BB_CITATIONS = "ReadingAgent_citations";
    private static final String BB_USED_RAG = "ReadingAgent_usedRAG";
    private static final String BB_REVISION_APPLIED = "ReadingAgent_revisionApplied";
    private static final String BB_TASK_INPUT = "ReadingAgent_taskInput";
    private static final String BB_TOPIC = "ReadingAgent_topic";
    private static final String BB_WEAK_POINTS = "ReadingAgent_weakPoints";

    private final ObjectMapper objectMapper;
    private final DeepSeekApiClient deepSeekApiClient;

    public ReadingAgent(ObjectMapper objectMapper, DeepSeekApiClient deepSeekApiClient,
                        AgentOrchestrationProperties orchestrationProps) {
        this.objectMapper = objectMapper;
        this.deepSeekApiClient = deepSeekApiClient;
        setOrchestrationProperties(orchestrationProps);
    }

    @Override
    public String getRole() {
        return "ReadingAgent";
    }

    @Override
    public Set<String> getSupportedTools() {
        return Set.of("RAGRetrieveTool", "ArtifactPersistTool");
    }

    @Override
    protected String getSystemPrompt() {
        return """
                你是 ReadingAgent，是面向计算机与人工智能课程的教材编写智能体。你的任务是生成学生可直接学习的完整拓展阅读章节，而不是只给资料推荐清单。

                严格规则：
                1. 正文目标长度 1200—5000 个中文字符，结构包含目录、目标、先修、3—6节正文、例子、误区、小结、思考题和延伸阅读。
                2. 正文解释“是什么、为什么、怎么做、何时使用”，不要用几条链接代替教学。
                3. 根据学生水平调整术语、推导和例子；公式解释变量，代码解释关键步骤。
                4. 事实性陈述优先使用 RAG 证据并保留 citationId，不编造论文、作者、链接、出处或 citationId。
                5. 无证据支撑的部分明确标注“通用教学说明，建议核对教材”；延伸阅读只列证据中真实存在的材料。

                仅输出可直接展示的中文 Markdown 教材正文。
                """;
    }

    @Override
    protected String buildRagQuery(AgentTask task, AgentContext context) {
        SharedBlackboard blackboard = context.blackboard();
        String topic = resolveTopic(task, blackboard);
        String weakPoints = resolveWeakPoints(task, blackboard);
        String revisionInstruction = Optional.ofNullable(task.input().get("revisionInstruction"))
                .map(Object::toString)
                .orElse("");

        blackboard.put(BB_TASK_INPUT, task.input());
        blackboard.put(BB_TOPIC, topic);
        blackboard.put(BB_WEAK_POINTS, weakPoints);
        blackboard.put(BB_REVISION_APPLIED, !revisionInstruction.isBlank());

        return (topic + " 教材 课程章节 核心原理 例题 常见误区 拓展阅读 " + weakPoints + " " + revisionInstruction).trim();
    }

    @Override
    protected String buildLlmPrompt(AgentTask task, String ragContext, AgentContext context) {
        SharedBlackboard blackboard = context.blackboard();
        String topic = Optional.ofNullable(blackboard.get(BB_TOPIC))
                .map(Object::toString)
                .orElse("course topic");
        String weakPoints = Optional.ofNullable(blackboard.get(BB_WEAK_POINTS))
                .map(Object::toString)
                .orElse("");

        String depth = String.valueOf(task.input().getOrDefault("depth", "INTERMEDIATE"));
        String revisionBlock = AgentPromptSupport.revisionBlock(task);

        int maxItems = 5;
        Object maxItemsRaw = task.input().get("maxItems");
        if (maxItemsRaw instanceof Number number) {
            maxItems = number.intValue();
        } else if (maxItemsRaw != null) {
            try {
                maxItems = Integer.parseInt(maxItemsRaw.toString());
            } catch (NumberFormatException ignored) {
                maxItems = 5;
            }
        }
        maxItems = Math.max(3, Math.min(10, maxItems));

        String profile = context.blackboard().getLearnerProfileSnapshot();
        if (profile == null || profile.isBlank()) {
            profile = String.valueOf(task.input().getOrDefault(
                    "learnerProfileSnapshot",
                    task.input().getOrDefault("learnerProfile", "{}")
            ));
        }

        String evidence = ragContext.isBlank()
                ? "本次没有知识库补充材料，请基于模型知识正常编写完整章节，且不要编造论文、链接或引用"
                : ragContext;

        return """
                请为“%s”编写一章 %s 难度的拓展教材正文。

                学生画像：%s
                薄弱点：%s

                可用的知识库证据：
                %s

                要求正文 1200—5000 个中文字符，至少包含一个具体例子、常见误区和三个带提示的思考题。
                延伸阅读最多 %d 项；只允许使用证据中真实存在的材料，并保留引用标识。
                """.formatted(
                topic,
                depth,
                profile.length() > 300 ? profile.substring(0, 300) : profile,
                weakPoints.isBlank() ? "待观察" : weakPoints,
                evidence,
                maxItems
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
                .orElse("course topic");
        String weakPoints = Optional.ofNullable(blackboard.get(BB_WEAK_POINTS))
                .map(Object::toString)
                .orElse("");

        return """
                # %s：拓展阅读学习包

                > 当前为透明降级版本：教材生成模型未配置或暂不可用。系统只展示可追溯证据与学习任务，不会虚构内容填充篇幅。

                ## 学习目标
                - 建立“%s”的概念地图，理解它解决的问题和适用边界。
                - 根据证据复述核心定义，并区分事实、推断与待核对内容。
                - 通过一个最小例子验证自己的理解。

                ## 薄弱点对齐
                %s

                ## 可追溯证据摘录
                %s

                ## 阅读与实践步骤
                1. 圈出三个关键词，分别回答“是什么、为什么、怎么用”。
                2. 画出关键词之间的依赖关系，把不确定的连线交给 AI 老师解释。
                3. 设计一个最小输入，手工走完一次处理流程并记录中间结果。
                4. 用课程教材核对结论，再把确认后的资料提交给管理员入库。

                ## 自测与反思
                1. 哪个相邻概念最容易与本主题混淆？判断依据是什么？
                2. 输入条件变化时，当前方法哪一步最可能先失效？
                3. 哪两个问题仍需要补充知识库证据才能可靠回答？
                """.formatted(
                topic,
                topic,
                weakPoints.isBlank() ? "尚未发现明确薄弱点，可从术语、流程和应用边界三方面自查。" : weakPoints,
                ragContext.isBlank()
                        ? "本次没有知识库补充材料，请基于模型知识正常完成教材章节，且不要编造外部来源。"
                        : ragContext.substring(0, Math.min(3200, ragContext.length()))
        );
    }

    @Override
    protected AgentResult buildResult(String generatedContent, List<String> citations, boolean usedRag,
                                      AgentTask task, AgentContext context) {
        SharedBlackboard blackboard = context.blackboard();
        String topic = Optional.ofNullable(blackboard.get(BB_TOPIC))
                .map(Object::toString)
                .orElse("course topic");
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
                "Extended reading for " + topic + (revisionApplied ? " revised from critic feedback" : ""),
                Instant.now()
        ));

        return new AgentResult(
                true,
                generatedContent,
                citations,
                Map.of(
                        "artifactType", "EXTENDED_READING",
                        "usedRAG", usedRag,
                        "agentLoop", "standard-pipeline",
                        "revisionApplied", revisionApplied
                ),
                List.of(new AgentHandoff("ReviewAgent", Map.of("artifactType", "EXTENDED_READING")))
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
        return "course topic";
    }

    private String resolveWeakPoints(AgentTask task, SharedBlackboard blackboard) {
        Object fromTask = task.input().get("weakPointsSnapshot");
        if (fromTask != null && !fromTask.toString().isBlank()) {
            return fromTask.toString();
        }
        Object fromBoard = blackboard.get("weakPointsSnapshot");
        return fromBoard != null ? fromBoard.toString() : "";
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

        ObjectNode persistArgs = objectMapper.createObjectNode();
        persistArgs.put("learningSessionId", learningSessionId);
        persistArgs.put("type", "EXTENDED_READING");
        persistArgs.put("title", topic + " extended reading");
        persistArgs.put("content", content);
        persistTool.execute(persistArgs, new ToolContext(context.blackboard(), context.runId(), Map.of()));
    }
}
