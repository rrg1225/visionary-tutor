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
 * DocSpecialistTool - 讲义生成专家工具
 * 独立实现：RAG检索 → Prompt构建 → LLM生成
 * 实现 SpecialistTool 接口支持动态工具注册
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocSpecialistTool implements SpecialistTool {

    public static final String TOOL_NAME = "generate_lecture_handout";

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
        String depth = ReActContext.getStringParam(args, "depth", "MEDIUM");
        String focusConcepts = ReActContext.getStringParam(args, "focusConcepts", "[]");
        Long learningSessionId = ReActContext.getLongParam(args, "learningSessionId");
        String memoryId = ReActContext.getStringParam(args, "memoryId", ctx.memoryId());

        return generateHandout(memoryId, topic, learnerProfile, depth, focusConcepts, learningSessionId);
    }

    @Tool(name = TOOL_NAME,
          value = "Generates comprehensive lecture handout with concepts, examples, and exercises. " +
                       "Use this when: 1) Student needs systematic concept explanation, 2) Starting a new topic, " +
                       "3) Need foundational material before practice. " +
                       "Parameters: topic (string), learnerProfile (JSON string), depth (SURFACE/MEDIUM/DEEP), " +
                       "focusConcepts (array string), learningSessionId (long).")
    @Transactional
    public String generateHandout(
            @ToolMemoryId String memoryId,
            String topic,
            String learnerProfile,
            String depth,
            String focusConcepts,
            Long learningSessionId) {

        log.info("[DocTool] Generating handout for topic='{}', depth='{}', sessionId={}, memoryId={}",
                topic, depth, learningSessionId, memoryId);

        try {
            // 1. RAG 检索
            RagRetrievalResult ragResult = ragRetrievalService.retrieveForTask(
                    AgentTaskType.RESOURCE_GENERATION,
                    topic + " 讲义 " + (focusConcepts != null ? focusConcepts : "")
            );
            String ragContext = formatRagContext(ragResult);
            List<RagCitation> citations = ragResult.citations();
            boolean usedRag = !ragContext.isBlank();

            // 2. 构建 Prompt
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(topic, learnerProfile, depth, focusConcepts, ragContext);

            // 3. LLM 生成
            String generatedContent;
            if (deepSeekApiClient.isConfigured()) {
                generatedContent = deepSeekApiClient.chat(systemPrompt, userPrompt, false);
            } else {
                generatedContent = buildFallbackContent(topic, ragContext);
            }

            return formatResult(generatedContent, citations, usedRag);

        } catch (java.net.SocketTimeoutException e) {
            log.warn("[DocTool] Timeout generating handout for topic='{}': {}", topic, e.getMessage());
            return ToolErrorMessageBuilder.timeoutError(TOOL_NAME, topic);
        } catch (Exception e) {
            log.error("[DocTool] Failed to generate handout: {}", e.getMessage(), e);
            return ToolErrorMessageBuilder.unknownError(TOOL_NAME, e.getMessage(), topic);
        }
    }

    private String buildSystemPrompt() {
        return """
                你是DocAgent，专门生成准确、完整的计算机视觉/深度学习课程讲义。

                严格规则：
                1. 以模型已有知识和用户主题完成讲义；RAG 仅作可选补充，实际采用时才保留 citationId
                2. 讲义结构：学习目标 → 核心概念 → 公式推导 → 代码示例 → 练习题
                3. 使用Markdown格式，代码块标注语言类型
                4. 复杂概念配合数学公式（LaTeX格式）
                5. 每节末尾给出小结和自测问题
                """;
    }

    private String buildUserPrompt(String topic, String learnerProfile, String depth,
                                    String focusConcepts, String ragContext) {
        String depthLevel = depth != null ? depth : "MEDIUM";

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
                learnerProfile != null ? learnerProfile.substring(0, Math.min(learnerProfile.length(), 500)) : "{}",
                focusConcepts != null ? focusConcepts : "[]",
                ragContext.isBlank() ? "本次没有知识库补充材料，请基于模型知识正常生成，且不要编造引用" : ragContext
        );
    }

    private String formatRagContext(RagRetrievalResult result) {
        if (result == null || result.groundedContextBlock().isBlank()) {
            return "";
        }
        return result.groundedContextBlock();
    }

    private String buildFallbackContent(String topic, String ragContext) {
        return "【讲义】" + topic + "\n\n"
                + (ragContext.isBlank()
                ? "（知识库证据不足，建议配置 RAG 后重新生成。）"
                : "基于检索证据生成的讲义预览：\n" + ragContext.substring(0, Math.min(800, ragContext.length())));
    }

    private String formatResult(String content, List<RagCitation> citations, boolean isGrounded) {
        try {
            ObjectNode result = objectMapper.createObjectNode();
            result.put("content", content);
            result.put("artifactType", "HANDOUT");
            result.put("isGrounded", isGrounded);
            result.put("citationCount", citations.size());
            result.set("citations", objectMapper.valueToTree(citations));
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        } catch (Exception e) {
            return content;
        }
    }
}
