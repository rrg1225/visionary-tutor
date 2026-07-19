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
import com.visionary.quiz.GeneratedQuizDocument;
import com.visionary.quiz.GeneratedQuizMarkdownRenderer;
import com.visionary.quiz.GeneratedQuizValidator;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Generates grounded practice questions using standard RAG + LLM pipeline.
 */
@Service
public class QuizAgent extends BaseSpecialistAgent {

    private static final String BB_CITATIONS = "QuizAgent_citations";
    private static final String BB_USED_RAG = "QuizAgent_usedRAG";
    private static final String BB_REVISION_APPLIED = "QuizAgent_revisionApplied";
    private static final String BB_TASK_INPUT = "QuizAgent_taskInput";
    private static final String BB_TOPIC = "QuizAgent_topic";
    private static final String BB_SUPPLEMENT_MODE = "QuizAgent_supplementMode";
    private static final String BB_QUESTION_COUNT = "QuizAgent_questionCount";

    /** 结构校验失败后允许的自动返修轮数（问题9：校验不过不得直接展示）。 */
    private static final int MAX_REPAIR_ROUNDS = 1;

    private final ObjectMapper objectMapper;
    private final DeepSeekApiClient deepSeekApiClient;
    private final AgentJsonParser agentJsonParser;

    public QuizAgent(ObjectMapper objectMapper, DeepSeekApiClient deepSeekApiClient,
                     AgentJsonParser agentJsonParser,
                     AgentOrchestrationProperties orchestrationProps) {
        this.objectMapper = objectMapper;
        this.deepSeekApiClient = deepSeekApiClient;
        this.agentJsonParser = agentJsonParser;
        setOrchestrationProperties(orchestrationProps);
    }

    @Override
    public String getRole() {
        return "QuizAgent";
    }

    @Override
    public Set<String> getSupportedTools() {
        return Set.of("RAGRetrieveTool", "ArtifactPersistTool");
    }

    @Override
    protected String getSystemPrompt() {
        return """
                你是QuizAgent，专门生成准确、完整的计算机视觉/深度学习练习题。

                严格规则：
                1. 以模型已有知识和用户主题生成题目；RAG 仅作可选补充，实际采用时才保留 citationId
                2. 题目分层：40%%基础题、40%%应用题、20%%综合题
                3. 题型覆盖：选择题（概念辨析）、简答题（原理理解）、计算题、代码纠错、综合题
                4. 每道题必须给出：难度、考查知识点、标准答案、得分点（含判分关键词）、详细解析、常见错误

                输出要求：只输出一个 JSON 对象，不要输出任何其他文字或 Markdown 围栏。结构如下：
                {
                  "schema": "generated-quiz/v1",
                  "topic": "主题",
                  "difficulty": "BASIC|INTERMEDIATE|ADVANCED",
                  "questions": [
                    {
                      "id": "q1",
                      "order": 1,
                      "type": "SINGLE_CHOICE|SHORT_ANSWER|CALCULATION|CODE_DEBUGGING|MULTI_STEP",
                      "difficulty": "BASIC|INTERMEDIATE|ADVANCED",
                      "knowledgePoints": ["知识点"],
                      "prompt": "题干（可用 Markdown，代码放 ``` 围栏内）",
                      "options": [{"key": "A", "text": "选项内容"}],
                      "standardAnswer": "标准答案（选择题填选项key）",
                      "scoringPoints": [{"description": "得分点描述", "acceptedKeywords": ["判分关键词"]}],
                      "explanation": "分步骤详细解析",
                      "commonErrors": ["常见错误"],
                      "recommendedReview": "推荐复习内容"
                    }
                  ]
                }

                硬性约束（违反会被程序拒绝并要求返修）：
                - 选择题 standardAnswer 必须等于某个选项的 key，且 options 至少 2 个
                - 非选择题必须提供至少 1 个得分点，且每个得分点都有 acceptedKeywords
                - explanation 不少于 10 个字；knowledgePoints、commonErrors 不能为空
                """;
    }

    @Override
    protected String buildRagQuery(AgentTask task, AgentContext context) {
        SharedBlackboard blackboard = context.blackboard();
        String topic = resolveTopic(task, blackboard);
        boolean supplementMode = Boolean.TRUE.equals(task.input().get("supplementMode"));
        String revisionInstruction = Optional.ofNullable(task.input().get("revisionInstruction"))
                .map(Object::toString)
                .orElse("");

        blackboard.put(BB_TASK_INPUT, task.input());
        blackboard.put(BB_TOPIC, topic);
        blackboard.put(BB_SUPPLEMENT_MODE, supplementMode);
        blackboard.put(BB_REVISION_APPLIED, !revisionInstruction.isBlank());

        if (supplementMode) {
            String targetGap = String.valueOf(task.input().getOrDefault("targetGapConcept", topic));
            return targetGap + " 练习题";
        }

        String query = (topic + " 练习题 " + revisionInstruction).trim();
        if (task.input().containsKey("difficulty")) {
            query = query + " " + task.input().get("difficulty");
        }
        return query.trim();
    }

    @Override
    protected String buildLlmPrompt(AgentTask task, String ragContext, AgentContext context) {
        SharedBlackboard blackboard = context.blackboard();
        String topic = Optional.ofNullable(blackboard.get(BB_TOPIC))
                .map(Object::toString)
                .orElse("练习");
        boolean supplementMode = Boolean.TRUE.equals(blackboard.get(BB_SUPPLEMENT_MODE));

        String revisionBlock = AgentPromptSupport.revisionBlock(task);
        String evidence = ragContext.isBlank()
                ? "本次没有知识库补充材料，请基于模型知识正常出题，且不要编造引用"
                : ragContext;

        if (supplementMode) {
            return buildSupplementPrompt(task, topic, evidence, revisionBlock);
        }

        int defaultCount = orchestrationProps != null ?
                orchestrationProps.getDefaultQuestionCount() : 6;
        int minCount = orchestrationProps != null ?
                orchestrationProps.getMinQuestionCount() : 3;
        int maxCount = orchestrationProps != null ?
                orchestrationProps.getMaxQuestionCount() : 15;

        int questionCount = defaultCount;
        Object countRaw = task.input().get("questionCount");
        if (countRaw instanceof Number number) {
            questionCount = number.intValue();
        } else if (countRaw != null) {
            try {
                questionCount = Integer.parseInt(countRaw.toString());
            } catch (NumberFormatException ignored) {
                questionCount = defaultCount;
            }
        }
        questionCount = Math.max(minCount, Math.min(maxCount, questionCount));
        blackboard.put(BB_QUESTION_COUNT, questionCount);

        String difficulty = String.valueOf(task.input().getOrDefault("difficulty", "INTERMEDIATE"));
        String profile = context.blackboard().getLearnerProfileSnapshot();
        if (profile == null || profile.isBlank()) {
            profile = String.valueOf(task.input().getOrDefault(
                    "learnerProfileSnapshot",
                    task.input().getOrDefault("learnerProfile", "{}")
            ));
        }

        int profileTruncateLength = orchestrationProps != null ?
                orchestrationProps.getQuizAgentProfileTruncateLength() : 500;

        return """
                生成 %d 道 %s 难度的练习题

                主题：%s

                学生画像（用于调整题目风格）：
                %s

                可选知识库补充材料（实际采用时标注 citationId）：
                %s

                请按系统约定的 JSON 结构输出完整题库。
                """.formatted(
                questionCount,
                difficulty,
                topic,
                profile.length() > profileTruncateLength ? profile.substring(0, profileTruncateLength) : profile,
                evidence
        ) + revisionBlock;
    }

    private String buildSupplementPrompt(AgentTask task, String topic, String ragContext, String revisionBlock) {
        String targetGapConcept = String.valueOf(task.input().getOrDefault("targetGapConcept", topic));
        String learnerWeakPoints = String.valueOf(task.input().getOrDefault("learnerWeakPoints", "待观察"));
        String existingQuizSummary = String.valueOf(task.input().getOrDefault("existingQuizSummary", ""));

        return """
                主题：%s
                现有题库摘要：%s
                需要补充的概念：%s

                可选知识库补充材料：
                %s

                请生成补充题库，按系统约定的 JSON 结构输出，明确标注难度和考查意图。
                """.formatted(
                topic,
                existingQuizSummary,
                targetGapConcept,
                ragContext.isBlank() ? "本次没有知识库补充材料，请基于模型知识正常出题，且不要编造引用" : ragContext
        ) + revisionBlock;
    }

    @Override
    protected String performLlmGeneration(String systemPrompt, String userPrompt, AgentTask task, AgentContext context) {
        if (deepSeekApiClient == null || !deepSeekApiClient.isConfigured()) {
            throw new IllegalStateException("DeepSeek API not configured");
        }
        int expectedCount = expectedQuestionCount(context);
        String raw = chat(systemPrompt, userPrompt);
        for (int round = 0; ; round++) {
            QuizParseOutcome outcome = parseAndValidate(raw, expectedCount);
            if (outcome.errors().isEmpty()) {
                return writeJson(outcome.document());
            }
            if (round >= MAX_REPAIR_ROUNDS) {
                throw new IllegalStateException("生成的题库未通过结构校验（已返修 " + round + " 轮）：" + outcome.errors());
            }
            log.warn("[QuizAgent] 题库结构校验未通过，自动返修（第 {} 轮）：{}", round + 1, outcome.errors());
            raw = chat(systemPrompt, buildRepairPrompt(raw, outcome.errors()));
        }
    }

    private String chat(String systemPrompt, String userPrompt) {
        try {
            return deepSeekApiClient.chat(systemPrompt, userPrompt, false);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private QuizParseOutcome parseAndValidate(String raw, int expectedCount) {
        GeneratedQuizDocument document;
        try {
            document = objectMapper.treeToValue(agentJsonParser.parseLenient(raw), GeneratedQuizDocument.class);
        } catch (Exception exception) {
            return new QuizParseOutcome(null, List.of("输出不是合法的题库 JSON：" + exception.getMessage()));
        }
        GeneratedQuizDocument normalized = GeneratedQuizValidator.normalize(document);
        return new QuizParseOutcome(normalized, GeneratedQuizValidator.validate(normalized, expectedCount));
    }

    private String buildRepairPrompt(String previousOutput, List<String> errors) {
        return """
                你上一次输出的题库未通过程序校验，存在以下问题：
                %s

                这是你上一次的输出：
                %s

                请修复以上全部问题，重新输出完整的题库 JSON（仍然只输出 JSON 对象，不要输出其他文字）。
                """.formatted("- " + String.join("\n- ", errors), truncate(previousOutput, 6000));
    }

    private int expectedQuestionCount(AgentContext context) {
        SharedBlackboard blackboard = context.blackboard();
        if (Boolean.TRUE.equals(blackboard.get(BB_SUPPLEMENT_MODE))) {
            return 0;
        }
        return blackboard.get(BB_QUESTION_COUNT) instanceof Integer count ? count : 0;
    }

    private String writeJson(GeneratedQuizDocument document) {
        try {
            return objectMapper.writeValueAsString(document);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot serialize generated quiz", exception);
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private record QuizParseOutcome(GeneratedQuizDocument document, List<String> errors) {
    }

    @Override
    protected String buildFallbackContent(AgentTask task, String ragContext, AgentContext context) {
        SharedBlackboard blackboard = context.blackboard();
        String topic = Optional.ofNullable(blackboard.get(BB_TOPIC))
                .map(Object::toString)
                .orElse("练习");
        boolean supplementMode = Boolean.TRUE.equals(blackboard.get(BB_SUPPLEMENT_MODE));

        if (supplementMode) {
            String targetGap = String.valueOf(task.input().getOrDefault("targetGapConcept", topic));
            return "【题库补充】" + topic + " - " + targetGap + "\n\n"
                    + (ragContext.isBlank()
                    ? "（知识库证据不足，建议配置 RAG 后重新生成。）"
                    : "基于检索证据生成补充练习题：\n" + ragContext.substring(0, Math.min(800, ragContext.length())));
        }

        return "【题库】" + topic + "\n\n"
                + (ragContext.isBlank()
                ? "（知识库证据不足，建议配置 RAG 后重新生成。）"
                : "基于检索证据生成不同难度练习题：\n" + ragContext.substring(0, Math.min(800, ragContext.length())));
    }

    @Override
    protected AgentResult buildResult(String generatedContent, List<String> citations, boolean usedRag,
                                      AgentTask task, AgentContext context) {
        SharedBlackboard blackboard = context.blackboard();
        String topic = Optional.ofNullable(blackboard.get(BB_TOPIC))
                .map(Object::toString)
                .orElse("练习");
        boolean revisionApplied = Boolean.TRUE.equals(blackboard.get(BB_REVISION_APPLIED));
        boolean supplementMode = Boolean.TRUE.equals(blackboard.get(BB_SUPPLEMENT_MODE));

        @SuppressWarnings("unchecked")
        Map<String, Object> taskInput = blackboard.get(BB_TASK_INPUT) instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();

        // 结构化路径：内容是通过校验的题库 JSON，展示层使用渲染出的 Markdown，判分层使用 JSON。
        // 回退路径：LLM 失败或返修仍未通过时的兜底文案，按历史 Markdown 形态保存。
        String contentJson = null;
        String displayContent = generatedContent;
        GeneratedQuizDocument document = tryParseDocument(generatedContent);
        if (document != null) {
            contentJson = generatedContent;
            displayContent = GeneratedQuizMarkdownRenderer.render(document);
        }

        persistArtifact(taskInput, context, topic, displayContent, contentJson, supplementMode);

        blackboard.put(BB_CITATIONS, new ArrayList<>(citations));
        blackboard.put(BB_USED_RAG, usedRag);

        blackboard.addTrace(new SharedBlackboard.AgentRunTrace(
                getRole(),
                "rag-llm-pipeline",
                "Quiz for " + topic + (revisionApplied ? " revised from critic feedback" : ""),
                Instant.now()
        ));

        return new AgentResult(
                true,
                displayContent,
                citations,
                Map.of(
                        "artifactType", "QUIZ",
                        "usedRAG", usedRag,
                        "agentLoop", "standard-pipeline",
                        "revisionApplied", revisionApplied,
                        "supplementMode", supplementMode,
                        "structured", document != null
                ),
                List.of()
        );
    }

    private GeneratedQuizDocument tryParseDocument(String content) {
        if (content == null || !content.stripLeading().startsWith("{")) {
            return null;
        }
        try {
            GeneratedQuizDocument document = objectMapper.readValue(content, GeneratedQuizDocument.class);
            return GeneratedQuizValidator.validate(document, 0).isEmpty() ? document : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String resolveTopic(AgentTask task, SharedBlackboard blackboard) {
        if (blackboard.getCurrentTopic() != null && !blackboard.getCurrentTopic().isBlank()) {
            return blackboard.getCurrentTopic();
        }
        Object topic = task.input().get("topic");
        if (topic != null && !topic.toString().isBlank()) {
            return topic.toString();
        }
        return "练习";
    }

    private void persistArtifact(
            Map<String, Object> taskInput,
            AgentContext context,
            String topic,
            String content,
            String contentJson,
            boolean supplementMode
    ) {
        Tool persistTool = context.tools().get("ArtifactPersistTool");
        if (persistTool == null || !taskInput.containsKey("learningSessionId")) {
            return;
        }

        Object sessionRaw = taskInput.get("learningSessionId");
        Long learningSessionId = sessionRaw instanceof Number number
                ? number.longValue()
                : Long.parseLong(sessionRaw.toString());

        String title = supplementMode
                ? topic + "-补充-" + taskInput.getOrDefault("targetGapConcept", "gap")
                : topic + " 练习题";

        ObjectNode args = objectMapper.createObjectNode();
        args.put("learningSessionId", learningSessionId);
        args.put("type", "QUIZ");
        args.put("title", title);
        args.put("content", content);
        if (contentJson != null && !contentJson.isBlank()) {
            args.put("contentJson", contentJson);
        }
        persistTool.execute(args, new ToolContext(context.blackboard(), context.runId(), Map.of()));
    }
}
