package com.visionary.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.visionary.agent.AgentJsonParser;
import com.visionary.agent.AgentTaskType;
import com.visionary.client.DeepSeekApiClient;
import com.visionary.quiz.GeneratedQuizDocument;
import com.visionary.quiz.GeneratedQuizMarkdownRenderer;
import com.visionary.quiz.GeneratedQuizValidator;
import com.visionary.rag.RagCitation;
import com.visionary.rag.RagRetrievalResult;
import com.visionary.rag.RagRetrievalService;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * QuizSpecialistTool - 题库生成专家工具
 * 独立实现：RAG检索 → Prompt构建 → LLM生成
 * 实现 SpecialistTool 接口支持动态工具注册
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuizSpecialistTool implements SpecialistTool {

    public static final String TOOL_NAME = "generate_quiz";
    public static final String SUPPLEMENT_TOOL_NAME = "supplement_quiz_for_gaps";
    private static final int MAX_REPAIR_ROUNDS = 1;

    private final ObjectMapper objectMapper;
    private final DeepSeekApiClient deepSeekApiClient;
    private final RagRetrievalService ragRetrievalService;
    private final AgentJsonParser agentJsonParser;

    @Override
    public String getToolName() {
        return TOOL_NAME;
    }

    @Override
    public String executeTool(ObjectNode args, ReActContext ctx) {
        String action = args.has("action") ? args.get("action").asText(TOOL_NAME) : TOOL_NAME;

        if (SUPPLEMENT_TOOL_NAME.equals(action)) {
            return executeSupplement(args, ctx);
        }

        String topic = ReActContext.getStringParam(args, "topic", ctx.topic());
        String learnerProfile = ReActContext.getStringParam(args, "learnerProfile", ctx.learnerProfile());
        String difficulty = ReActContext.getStringParam(args, "difficulty", "INTERMEDIATE");
        int questionCount = ReActContext.getIntParam(args, "questionCount", 6);
        Long learningSessionId = ReActContext.getLongParam(args, "learningSessionId");
        String memoryId = ReActContext.getStringParam(args, "memoryId", ctx.memoryId());

        return generateQuiz(memoryId, topic, learnerProfile, difficulty, questionCount, learningSessionId);
    }

    private String executeSupplement(ObjectNode args, ReActContext ctx) {
        String topic = ReActContext.getStringParam(args, "topic", ctx.topic());
        String existingQuizSummary = ReActContext.getStringParam(args, "existingQuizSummary", "");
        String targetGapConcept = ReActContext.getStringParam(args, "targetGapConcept", "");
        String learnerWeakPoints = ReActContext.getStringParam(args, "learnerWeakPoints", ctx.weakPoints());
        Long learningSessionId = ReActContext.getLongParam(args, "learningSessionId");
        String memoryId = ReActContext.getStringParam(args, "memoryId", ctx.memoryId());

        return supplementQuizForGaps(memoryId, topic, existingQuizSummary, targetGapConcept, learnerWeakPoints, learningSessionId);
    }

    @Tool(name = TOOL_NAME,
          value = "Generates hierarchical practice questions (choice, short-answer, coding) for a specific topic. " +
                       "Use this when: 1) Student needs practice on a concept, 2) Supervisor needs to assess mastery, " +
                       "3) Critic identifies gaps in existing materials. " +
                       "Parameters: topic (string), learnerProfile (JSON string), difficulty (BEGINNER/INTERMEDIATE/ADVANCED), " +
                       "questionCount (integer 5-10), learningSessionId (long).")
    @Transactional
    public String generateQuiz(
            @ToolMemoryId String memoryId,
            String topic,
            String learnerProfile,
            String difficulty,
            int questionCount,
            Long learningSessionId) {

        log.info("[QuizTool] Generating quiz for topic='{}', difficulty='{}', sessionId={}",
                topic, difficulty, learningSessionId);

        // 参数校验
        if (questionCount < 3 || questionCount > 15) {
            questionCount = Math.max(3, Math.min(15, questionCount));
        }
        String diff = (difficulty == null || difficulty.isBlank()) ? "INTERMEDIATE" : difficulty;

        try {
            // 1. RAG 检索
            RagRetrievalResult ragResult = ragRetrievalService.retrieveForTask(
                    AgentTaskType.RESOURCE_GENERATION,
                    topic + " 练习题 " + diff
            );
            String ragContext = formatRagContext(ragResult);
            List<RagCitation> citations = ragResult.citations();
            boolean usedRag = !ragContext.isBlank();

            // 2. 构建 Prompt
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(topic, learnerProfile, diff, questionCount, ragContext, false, null);

            // 3. LLM 生成
            GeneratedQuizDocument document;
            if (deepSeekApiClient.isConfigured()) {
                document = generateValidatedDocument(systemPrompt, userPrompt, questionCount);
            } else {
                document = buildFallbackDocument(topic, diff, questionCount, null);
            }

            return formatResult(document, citations, usedRag);

        } catch (java.net.SocketTimeoutException e) {
            log.warn("[QuizTool] Timeout generating quiz for topic='{}': {}", topic, e.getMessage());
            return ToolErrorMessageBuilder.timeoutError(TOOL_NAME, topic);
        } catch (Exception e) {
            log.error("[QuizTool] Failed to generate quiz: {}", e.getMessage(), e);
            return ToolErrorMessageBuilder.unknownError(TOOL_NAME, e.getMessage(), topic);
        }
    }

    @Tool(name = SUPPLEMENT_TOOL_NAME,
          value = "Generates additional quiz questions targeting identified knowledge gaps. " +
                       "Use this when: 1) Critic identifies missing concept coverage, " +
                       "2) Student's weak points need targeted practice, " +
                       "3) Existing quiz lacks questions on specific sub-topics.")
    public String supplementQuizForGaps(
            @ToolMemoryId String memoryId,
            String topic,
            String existingQuizSummary,
            String targetGapConcept,
            String learnerWeakPoints,
            Long learningSessionId) {

        log.info("[QuizTool] Supplementing quiz for gap='{}' in topic='{}'", targetGapConcept, topic);

        try {
            // 1. RAG 检索（针对薄弱点）
            RagRetrievalResult ragResult = ragRetrievalService.retrieveForTask(
                    AgentTaskType.RESOURCE_GENERATION,
                    targetGapConcept + " 练习题 补充"
            );
            String ragContext = formatRagContext(ragResult);
            List<RagCitation> citations = ragResult.citations();

            // 2. 构建 Prompt（补充模式）
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(topic, learnerWeakPoints, "INTERMEDIATE", 5, ragContext, true, targetGapConcept);

            // 3. LLM 生成
            GeneratedQuizDocument document;
            if (deepSeekApiClient.isConfigured()) {
                document = generateValidatedDocument(systemPrompt, userPrompt, 5);
            } else {
                document = buildFallbackDocument(topic, "INTERMEDIATE", 5, targetGapConcept);
            }

            return formatResult(document, citations, !ragContext.isBlank());

        } catch (java.net.SocketTimeoutException e) {
            log.warn("[QuizTool] Timeout supplementing quiz for topic='{}': {}", topic, e.getMessage());
            return ToolErrorMessageBuilder.timeoutError(SUPPLEMENT_TOOL_NAME, topic);
        } catch (Exception e) {
            log.error("[QuizTool] Failed to supplement quiz: {}", e.getMessage());
            return ToolErrorMessageBuilder.unknownError(SUPPLEMENT_TOOL_NAME, e.getMessage(), topic);
        }
    }

    private String buildSystemPrompt() {
        return """
                你是QuizAgent，专门生成准确、完整的计算机视觉/深度学习练习题。

                严格规则：
                1. 以模型已有知识和用户主题生成题目；RAG 仅作可选补充，实际采用时才保留 citationId
                2. 题目分层：40%%基础题、40%%应用题、20%%综合题
                3. 题型可使用 SINGLE_CHOICE、SHORT_ANSWER、CALCULATION、CODE_DEBUGGING、MULTI_STEP
                4. 每道题必须提供知识点、标准答案、得分点、详细解析、常见错误和推荐复习内容

                只输出 JSON，不得输出 Markdown 围栏或说明文字：
                {
                  "schema":"generated-quiz/v1",
                  "topic":"主题",
                  "difficulty":"BASIC|INTERMEDIATE|ADVANCED",
                  "questions":[{
                    "id":"q1","order":1,"type":"SINGLE_CHOICE|SHORT_ANSWER|CALCULATION|CODE_DEBUGGING|MULTI_STEP",
                    "difficulty":"BASIC|INTERMEDIATE|ADVANCED","knowledgePoints":["知识点"],"prompt":"题干",
                    "options":[{"key":"A","text":"选项"}],"standardAnswer":"标准答案",
                    "scoringPoints":[{"description":"得分点","acceptedKeywords":["关键词"]}],
                    "explanation":"分步骤解析","commonErrors":["常见错误"],"recommendedReview":"推荐复习"
                  }]
                }

                选择题至少两个选项且答案必须为选项 key；非选择题必须提供含判分关键词的得分点。
                """;
    }

    private String buildUserPrompt(String topic, String learnerProfile, String difficulty,
                                    int questionCount, String ragContext,
                                    boolean supplementMode, String targetGap) {
        if (supplementMode) {
            return """
                    主题：%s
                    需要补充的概念：%s
                    学生薄弱点：%s

                    证据材料：
                    %s

                    请生成5道专门针对该薄弱概念的补充练习题。
                    知识库材料仅作可选补充；没有材料时仍须基于模型知识正常完成题库。
                    严格按照系统约定的 generated-quiz/v1 JSON 输出。
                    """.formatted(
                    topic,
                    targetGap != null ? targetGap : topic,
                    learnerProfile != null ? learnerProfile.substring(0, Math.min(learnerProfile.length(), 300)) : "待观察",
                    ragContext.isBlank() ? "本次没有知识库补充材料，请基于模型知识正常出题，且不要编造引用" : ragContext
            );
        }

        return """
                生成 %d 道 %s 难度的练习题

                主题：%s

                学生画像（用于调整题目风格）：
                %s

                可选知识库补充材料（实际采用时标注 citationId）：
                %s

                严格按照系统约定的 generated-quiz/v1 JSON 输出完整题库。
                """.formatted(
                questionCount,
                difficulty,
                topic,
                learnerProfile != null ? learnerProfile.substring(0, Math.min(learnerProfile.length(), 500)) : "{}",
                ragContext.isBlank() ? "本次没有知识库补充材料，请基于模型知识正常出题，且不要编造引用" : ragContext
        );
    }

    private String formatRagContext(RagRetrievalResult result) {
        if (result == null || result.groundedContextBlock().isBlank()) {
            return "";
        }
        return result.groundedContextBlock();
    }

    private GeneratedQuizDocument generateValidatedDocument(
            String systemPrompt,
            String userPrompt,
            int expectedQuestionCount
    ) throws IOException {
        String raw = deepSeekApiClient.chat(systemPrompt, userPrompt, false);
        for (int round = 0; ; round++) {
            QuizParseOutcome outcome = parseAndValidate(raw, expectedQuestionCount);
            if (outcome.errors().isEmpty()) {
                return outcome.document();
            }
            if (round >= MAX_REPAIR_ROUNDS) {
                throw new IllegalStateException("ReAct 题库未通过结构校验：" + outcome.errors());
            }
            log.warn("[QuizTool] Structured quiz repair round {}: {}", round + 1, outcome.errors());
            raw = deepSeekApiClient.chat(systemPrompt, buildRepairPrompt(raw, outcome.errors()), false);
        }
    }

    private QuizParseOutcome parseAndValidate(String raw, int expectedQuestionCount) {
        try {
            GeneratedQuizDocument parsed = objectMapper.treeToValue(
                    agentJsonParser.parseLenient(raw), GeneratedQuizDocument.class);
            GeneratedQuizDocument normalized = GeneratedQuizValidator.normalize(parsed);
            return new QuizParseOutcome(
                    normalized,
                    GeneratedQuizValidator.validate(normalized, expectedQuestionCount)
            );
        } catch (Exception exception) {
            return new QuizParseOutcome(null, List.of("输出不是合法的题库 JSON：" + exception.getMessage()));
        }
    }

    private String buildRepairPrompt(String previousOutput, List<String> errors) {
        String output = previousOutput == null ? "" : previousOutput;
        return """
                上一次题库未通过程序校验：
                - %s

                上一次输出：
                %s

                请修复全部问题并重新输出完整 generated-quiz/v1 JSON，不要输出其他文字。
                """.formatted(
                String.join("\n- ", errors),
                output.substring(0, Math.min(output.length(), 6000))
        );
    }

    private GeneratedQuizDocument buildFallbackDocument(
            String topic,
            String difficulty,
            int questionCount,
            String targetGap
    ) {
        String subject = targetGap == null || targetGap.isBlank() ? topic : targetGap;
        String normalizedDifficulty = switch (difficulty == null ? "" : difficulty.toUpperCase()) {
            case "BEGINNER", "BASIC" -> "BASIC";
            case "ADVANCED" -> "ADVANCED";
            default -> "INTERMEDIATE";
        };
        List<GeneratedQuizDocument.Question> questions = new ArrayList<>();
        for (int index = 1; index <= questionCount; index++) {
            boolean choice = index % 2 == 1;
            List<GeneratedQuizDocument.Option> options = choice
                    ? List.of(
                            new GeneratedQuizDocument.Option("A", "只记住术语，不验证条件"),
                            new GeneratedQuizDocument.Option("B", "说明关键条件，并用例子或实验验证"),
                            new GeneratedQuizDocument.Option("C", "忽略输入输出契约"),
                            new GeneratedQuizDocument.Option("D", "只比较最终结论")
                    ) : List.of();
            List<GeneratedQuizDocument.ScoringPoint> scoringPoints = choice
                    ? List.of()
                    : List.of(
                            new GeneratedQuizDocument.ScoringPoint(
                                    "说明关键概念或条件", List.of(subject, "条件", "概念")),
                            new GeneratedQuizDocument.ScoringPoint(
                                    "给出验证方法", List.of("验证", "实验", "例子"))
                    );
            questions.add(new GeneratedQuizDocument.Question(
                    "q" + index,
                    index,
                    choice ? "SINGLE_CHOICE" : "SHORT_ANSWER",
                    normalizedDifficulty,
                    List.of(subject),
                    choice
                            ? "关于“" + subject + "”的学习，下列哪一种做法最可靠？"
                            : "请说明“" + subject + "”的一个关键条件，并给出可验证的方法。",
                    options,
                    choice ? "B" : "先说明关键条件，再通过例子、计算或实验验证结论。",
                    scoringPoints,
                    "可靠学习需要明确适用条件，并通过可以复现的例子、计算或实验检查结论。",
                    List.of("只背结论而忽略适用条件", "没有说明验证过程"),
                    subject + " 的关键条件与验证方法"
            ));
        }
        return new GeneratedQuizDocument(
                GeneratedQuizDocument.SCHEMA_V1,
                topic,
                normalizedDifficulty,
                List.copyOf(questions)
        );
    }

    private String formatResult(
            GeneratedQuizDocument document,
            List<RagCitation> citations,
            boolean isGrounded
    ) {
        try {
            ObjectNode result = objectMapper.createObjectNode();
            result.put("content", GeneratedQuizMarkdownRenderer.render(document));
            result.put("contentJson", objectMapper.writeValueAsString(document));
            result.put("schema", GeneratedQuizDocument.SCHEMA_V1);
            result.put("artifactType", "QUIZ");
            result.put("isGrounded", isGrounded);
            result.put("citationCount", citations.size());
            result.set("citations", objectMapper.valueToTree(citations));
            result.put("summary", "Generated " + (isGrounded ? "grounded" : "template-based")
                    + " quiz with " + citations.size() + " citations");
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize structured quiz result", e);
        }
    }

    private record QuizParseOutcome(GeneratedQuizDocument document, List<String> errors) {
    }
}
