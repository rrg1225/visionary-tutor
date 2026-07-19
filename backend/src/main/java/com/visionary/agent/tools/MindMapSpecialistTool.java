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
 * MindMapSpecialistTool - 思维导图生成专家工具
 * 独立实现：可选 RAG 检索 → Prompt构建 → LLM生成
 * 实现 SpecialistTool 接口支持动态工具注册
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MindMapSpecialistTool implements SpecialistTool {

    public static final String TOOL_NAME = "generate_mind_map";

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
        String detailLevel = ReActContext.getStringParam(args, "detailLevel", "EXPANDED");
        Long learningSessionId = ReActContext.getLongParam(args, "learningSessionId");
        String memoryId = ReActContext.getStringParam(args, "memoryId", ctx.memoryId());

        return generateMindMap(memoryId, topic, learnerProfile, detailLevel, learningSessionId);
    }

    @Tool(name = TOOL_NAME,
          value = "Generates Mermaid mind map for knowledge structure visualization. " +
                       "Use this when: 1) Student needs to understand knowledge hierarchy, 2) Overview of topic structure, " +
                       "3) Connecting concepts before deep dive. " +
                       "Parameters: topic (string), learnerProfile (JSON string), detailLevel (CORE/EXPANDED/COMPREHENSIVE), " +
                       "learningSessionId (long).")
    @Transactional
    public String generateMindMap(
            @ToolMemoryId String memoryId,
            String topic,
            String learnerProfile,
            String detailLevel,
            Long learningSessionId) {

        log.info("[MindMapTool] Generating mind map for topic='{}', level='{}', sessionId={}",
                topic, detailLevel, learningSessionId);

        try {
            // 1. RAG 检索
            RagRetrievalResult ragResult = ragRetrievalService.retrieveForTask(
                    AgentTaskType.RESOURCE_GENERATION,
                    topic + " 思维导图 知识结构"
            );
            String ragContext = formatRagContext(ragResult);
            List<RagCitation> citations = ragResult.citations();
            boolean usedRag = !ragContext.isBlank();

            // 2. 构建 Prompt
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(topic, learnerProfile, detailLevel, ragContext);

            // 3. LLM 生成
            String generatedContent;
            if (deepSeekApiClient.isConfigured()) {
                generatedContent = deepSeekApiClient.chat(systemPrompt, userPrompt, false);
            } else {
                generatedContent = buildFallbackContent(topic, ragContext);
            }

            return formatResult(generatedContent, citations, usedRag);

        } catch (java.net.SocketTimeoutException e) {
            log.warn("[MindMapTool] Timeout generating mind map for topic='{}': {}", topic, e.getMessage());
            return ToolErrorMessageBuilder.timeoutError(TOOL_NAME, topic);
        } catch (Exception e) {
            log.error("[MindMapTool] Failed: {}", e.getMessage());
            return ToolErrorMessageBuilder.unknownError(TOOL_NAME, e.getMessage(), topic);
        }
    }

    private String buildSystemPrompt() {
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

    private String buildUserPrompt(String topic, String learnerProfile, String detailLevel, String ragContext) {
        String level = detailLevel != null ? detailLevel : "EXPANDED";

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
                level,
                learnerProfile != null ? learnerProfile.substring(0, Math.min(learnerProfile.length(), 300)) : "{}",
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
        String contentPart = ragContext.isBlank() ? "概念\n    子概念" : ragContext.substring(0, Math.min(400, ragContext.length()));

        return "# " + topic + " 思维导图\n\n"
                + "```mermaid\n"
                + "mindmap\n  root((" + topic + "))\n    "
                + contentPart.replace("\n", "\n    ")
                + "\n```\n\n（证据来源已标注）";
    }

    private String formatResult(String content, List<RagCitation> citations, boolean isGrounded) {
        try {
            ObjectNode result = objectMapper.createObjectNode();
            result.put("content", content);
            result.put("artifactType", "MINDMAP");
            result.put("isGrounded", isGrounded);
            result.put("citationCount", citations.size());
            result.set("citations", objectMapper.valueToTree(citations));
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        } catch (Exception e) {
            return content;
        }
    }
}
