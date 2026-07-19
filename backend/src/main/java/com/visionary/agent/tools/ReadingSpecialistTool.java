package com.visionary.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.visionary.agent.AgentTaskType;
import com.visionary.client.DeepSeekApiClient;
import com.visionary.rag.RagCitation;
import com.visionary.rag.RagRetrievalResult;
import com.visionary.rag.RagRetrievalService;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ReadingSpecialistTool - 拓展阅读生成专家工具
 * 独立实现：RAG检索 → Prompt构建 → LLM生成
 * 实现 SpecialistTool 接口支持动态工具注册
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReadingSpecialistTool implements SpecialistTool {

    public static final String TOOL_NAME = "generate_reading_materials";

    private final ObjectMapper objectMapper;
    private final DeepSeekApiClient deepSeekApiClient;
    private final RagRetrievalService ragRetrievalService;

    @Override
    public String getToolName() {
        return TOOL_NAME;
    }

    @Override
    public String executeTool(ObjectNode args, ReActContext ctx) {
        String topic = ReActContext.getStringParam(args, "topic", ctx.topic());
        String learnerProfile = ReActContext.getStringParam(args, "learnerProfile", ctx.learnerProfile());
        String depth = ReActContext.getStringParam(args, "depth", "INTERMEDIATE");
        int maxItems = ReActContext.getIntParam(args, "maxItems", 5);
        Long learningSessionId = ReActContext.getLongParam(args, "learningSessionId");
        String memoryId = ReActContext.getStringParam(args, "memoryId", ctx.memoryId());

        return generateReadingMaterials(memoryId, topic, learnerProfile, depth, maxItems, learningSessionId);
    }

    @Tool(name = TOOL_NAME,
          value = "Generates a complete evidence-grounded extended-reading chapter, with explanations, examples, " +
                       "reflection questions, and a small number of verified further-reading recommendations. " +
                       "Use this when a student wants to understand a topic beyond a short answer. " +
                       "Parameters: topic (string), learnerProfile (JSON string), " +
                       "depth (INTRODUCTORY/INTERMEDIATE/RESEARCH), maxItems (int 3-8), " +
                       "learningSessionId (long).")
    @Transactional
    public String generateReadingMaterials(
            @ToolMemoryId String memoryId,
            String topic,
            String learnerProfile,
            String depth,
            int maxItems,
            Long learningSessionId) {

        log.info("[ReadingTool] Generating reading materials for topic='{}', depth='{}', sessionId={}, memoryId={}",
                topic, depth, learningSessionId, memoryId);

        maxItems = Math.max(3, Math.min(10, maxItems));
        String d = (depth == null || depth.isBlank()) ? "INTERMEDIATE" : depth;

        try {
            // 1. RAG 检索
            RagRetrievalResult ragResult = ragRetrievalService.retrieveForTask(
                    AgentTaskType.RESOURCE_GENERATION,
                    topic + " 拓展阅读 论文推荐 书籍 " + d
            );
            String ragContext = formatRagContext(ragResult);
            List<RagCitation> citations = ragResult.citations();
            boolean usedRag = !ragContext.isBlank();

            // 2. 构建 Prompt
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(topic, learnerProfile, d, maxItems, ragContext);

            // 3. LLM 生成
            String generatedContent;
            if (deepSeekApiClient.isConfigured()) {
                generatedContent = deepSeekApiClient.chat(systemPrompt, userPrompt, false);
            } else {
                generatedContent = buildFallbackContent(topic, maxItems, d, ragContext);
            }

            return formatResult(generatedContent, citations, usedRag);

        } catch (java.net.SocketTimeoutException e) {
            log.warn("[ReadingTool] Timeout generating reading materials for topic='{}': {}", topic, e.getMessage());
            return ToolErrorMessageBuilder.timeoutError(TOOL_NAME, topic);
        } catch (Exception e) {
            log.error("[ReadingTool] Failed: {}", e.getMessage());
            return ToolErrorMessageBuilder.unknownError(TOOL_NAME, e.getMessage(), topic);
        }
    }

    private String buildSystemPrompt() {
        return """
                你是 ReadingAgent，是面向计算机与人工智能课程的教材编写智能体。你的首要任务是写出学生可以直接阅读和学习的完整章节，而不是只给资料推荐清单。

                严格规则：
                1. 正文目标长度为 1200—5000 个中文字符；先给结论和学习地图，再逐层解释。
                2. 固定结构：目录、学习目标、先修知识、核心正文（3—6节）、至少1个具体例子、常见误区、本章小结、自测/思考题、延伸阅读。
                3. 核心正文必须解释“是什么、为什么、怎么做、何时使用”，不能用推荐列表代替教学正文。
                4. 根据学生水平调整术语密度和推导深度；遇到公式要解释变量含义，遇到代码要解释关键步骤。
                5. 事实性陈述优先依据知识库证据，沿用证据中的 citationId；不得编造 citationId、论文、作者、链接或出处。
                6. 知识库没有支撑的内容要标注“通用教学说明，建议核对教材”，不把它伪装成已验证来源。
                7. 延伸阅读只推荐证据中真实出现的材料；没有可靠材料时给出“检索方向”，不要虚构书目。

                仅输出可直接展示的中文 Markdown 教材正文，不输出生成过程或 JSON。
                """;
    }

    private String buildUserPrompt(String topic, String learnerProfile, String depth,
                                    int maxItems, String ragContext) {
        return """
                请为“%s”编写一章 %s 难度的拓展教材正文。

                学生画像：%s

                可用的知识库证据：
                %s

                写作要求：
                - 正文控制在 1200—5000 个中文字符，内容完整，不用几行提纲敷衍；
                - 目录可点击不是必需，但章节层级必须清晰；
                - 至少包含一个可操作例子和三个带提示的思考题；
                - 延伸阅读最多列出 %d 项，并严格遵守证据边界；
                - 保留证据中的引用标识，便于答案追溯。
                """.formatted(
                topic,
                depth,
                learnerProfile != null ? learnerProfile.substring(0, Math.min(learnerProfile.length(), 300)) : "{}",
                ragContext.isBlank() ? "本次没有知识库补充材料，请基于模型知识正常完成教材章节，且不要编造外部来源。" : ragContext,
                maxItems
        );
    }

    private String formatRagContext(RagRetrievalResult result) {
        if (result == null || result.groundedContextBlock().isBlank()) {
            return "";
        }
        return result.groundedContextBlock();
    }

    private String buildFallbackContent(String topic, int maxItems, String depth, String ragContext) {
        String evidence = ragContext == null || ragContext.isBlank()
                ? "当前没有可用的知识库证据，因此系统不会自动扩写未经验证的教材事实。"
                : ragContext.substring(0, Math.min(3200, ragContext.length()));
        return """
                # %s：拓展阅读学习包

                > 当前为透明降级版本：教材生成模型未配置或暂不可用。下面保留可追溯证据和学习任务，不会用虚构内容填满篇幅。

                ## 学习目标
                - 建立“%s”的概念地图，区分核心概念、实现步骤与适用边界。
                - 能从证据中找出关键定义，并用自己的语言复述。
                - 完成一个最小实践或例题，把阅读结果转化为可验证的理解。

                ## 建议深度
                %s（延伸材料上限 %d 项）

                ## 可追溯证据摘录
                %s

                ## 阅读与实践步骤
                1. 从证据中圈出三个关键词，分别回答“它是什么、解决什么问题、有什么限制”。
                2. 画出关键词之间的依赖关系；不确定的连线交给 AI 老师解释，不直接当成事实。
                3. 找一个最小输入，手工走完一次处理流程，并记录每一步的输入与输出。
                4. 将结果与教材或课程资料交叉核对，再把确认后的资料交给管理员入库。

                ## 自测与反思
                1. 这个主题最容易与哪个相邻概念混淆？判断依据是什么？
                2. 如果输入条件发生变化，原方法的哪一步最先失效？
                3. 你还能提出哪两个需要知识库证据才能回答的问题？
                """.formatted(topic, topic, depth, maxItems, evidence);
    }

    private String formatResult(String content, List<RagCitation> citations, boolean isGrounded) {
        try {
            ObjectNode result = objectMapper.createObjectNode();
            result.put("content", content);
            result.put("artifactType", "EXTENDED_READING");
            result.put("isGrounded", isGrounded);
            result.put("citationCount", citations.size());
            result.set("citations", objectMapper.valueToTree(citations));
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        } catch (Exception e) {
            return content;
        }
    }
}
