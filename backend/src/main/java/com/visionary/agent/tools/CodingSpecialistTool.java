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
 * CodingSpecialistTool - 代码实操生成专家工具
 * 独立实现：RAG检索 → Prompt构建 → LLM生成
 * 实现 SpecialistTool 接口支持动态工具注册
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CodingSpecialistTool implements SpecialistTool {

    public static final String TOOL_NAME = "generate_coding_practice";

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
        String difficulty = ReActContext.getStringParam(args, "difficulty", "GUIDED");
        boolean includeSolutions = ReActContext.getBoolParam(args, "includeSolutions", false);
        Long learningSessionId = ReActContext.getLongParam(args, "learningSessionId");
        String memoryId = ReActContext.getStringParam(args, "memoryId", ctx.memoryId());

        return generateCodingPractice(memoryId, topic, learnerProfile, difficulty, includeSolutions, learningSessionId);
    }

    @Tool(name = TOOL_NAME,
          value = "Generates hands-on coding exercises with PyTorch/TensorFlow. " +
                       "Use this when: 1) Student has completed concept learning and needs hands-on practice, " +
                       "2) Preparing for practical implementation, 3) Reinforcing theory with code. " +
                       "Parameters: topic (string), learnerProfile (JSON string), " +
                       "difficulty (GUIDED/CHALLENGE/PROJECT), includeSolutions (boolean), " +
                       "learningSessionId (long).")
    @Transactional
    public String generateCodingPractice(
            @ToolMemoryId String memoryId,
            String topic,
            String learnerProfile,
            String difficulty,
            boolean includeSolutions,
            Long learningSessionId) {

        log.info("[CodingTool] Generating code practice for topic='{}', difficulty='{}', sessionId={}",
                topic, difficulty, learningSessionId);

        try {
            // 1. RAG 检索
            RagRetrievalResult ragResult = ragRetrievalService.retrieveForTask(
                    AgentTaskType.RESOURCE_GENERATION,
                    topic + " code practice " + (difficulty != null ? difficulty : "")
            );
            String ragContext = formatRagContext(ragResult);
            List<RagCitation> citations = ragResult.citations();
            boolean usedRag = !ragContext.isBlank();

            // 2. 构建 Prompt
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(topic, learnerProfile, difficulty, includeSolutions, ragContext);

            // 3. LLM 生成
            String generatedContent;
            if (deepSeekApiClient.isConfigured()) {
                generatedContent = deepSeekApiClient.chat(systemPrompt, userPrompt, false);
            } else {
                generatedContent = buildFallbackContent(topic, ragContext, difficulty, includeSolutions);
            }

            return formatResult(generatedContent, citations, usedRag);

        } catch (java.net.SocketTimeoutException e) {
            log.warn("[CodingTool] Timeout generating coding practice for topic='{}': {}", topic, e.getMessage());
            return ToolErrorMessageBuilder.timeoutError(TOOL_NAME, topic);
        } catch (Exception e) {
            log.error("[CodingTool] Failed: {}", e.getMessage());
            return ToolErrorMessageBuilder.unknownError(TOOL_NAME, e.getMessage(), topic);
        }
    }

    private String buildSystemPrompt() {
        return """
                你是CodingAgent，专门生成PyTorch深度学习代码实操案例。

                严格规则：
                1. 所有代码必须可运行（Python 3.8+, PyTorch 2.0+）
                2. 代码包含完整注释，解释关键步骤
                3. 提供shape调试打印，帮助理解tensor变化
                4. 处理常见错误（如维度不匹配、设备问题）
                5. 难度分级：
                   - GUIDED: 填空式代码，80%%预填，学生补20%%
                   - CHALLENGE: 给出要求，学生独立实现
                   - PROJECT: 综合项目，多模块协作

                输出Markdown格式，代码块标注python。
                """;
    }

    private String buildUserPrompt(String topic, String learnerProfile, String difficulty,
                                    boolean includeSolutions, String ragContext) {
        String diff = difficulty != null ? difficulty : "GUIDED";

        return """
                生成"%s"的%s难度代码实操

                学生画像：%s
                %s

                参考资料：
                %s

                请输出完整代码实操案例（Markdown格式）。
                """.formatted(
                topic,
                diff,
                learnerProfile != null ? learnerProfile.substring(0, Math.min(learnerProfile.length(), 300)) : "{}",
                includeSolutions ? "（需要包含完整解答）" : "（不包含解答，让学生独立完成）",
                ragContext.isBlank() ? "No optional knowledge-base context. Generate normally from model knowledge." : ragContext
        );
    }

    private String formatRagContext(RagRetrievalResult result) {
        if (result == null || result.groundedContextBlock().isBlank()) {
            return "";
        }
        return result.groundedContextBlock();
    }

    private String buildFallbackContent(String topic, String ragContext, String difficulty, boolean includeSolutions) {
        String diff = difficulty != null ? difficulty : "GUIDED";
        String solutionSection = includeSolutions ? """
                ### 参考答案（完整实现）
                ```python
                # 此处应有完整实现代码
                # 配置DeepSeek API后可获取
                ```
                """ : """
                ### 验证标准
                你的代码应该能够：
                - 正确初始化模型
                - 处理输入数据并输出预期shape
                - 无明显运行时错误
                """;

        return """
                # %s 代码实操（%s难度）

                ## 任务目标
                实现%s的核心代码，理解其工作原理。

                ## 环境准备
                ```bash
                pip install torch torchvision matplotlib
                ```

                ## 代码框架
                ```python
                import torch
                import torch.nn as nn
                print(f"PyTorch version: {torch.__version__}")
                ```

                %s

                ## 参考资料摘要
                %s

                > 提示：配置DeepSeek API后可获取完整可运行代码。
                """.formatted(
                topic,
                diff,
                topic,
                solutionSection,
                ragContext.isBlank() ? "（知识库证据不足）" : ragContext.substring(0, Math.min(400, ragContext.length()))
        );
    }

    private String formatResult(String content, List<RagCitation> citations, boolean isGrounded) {
        try {
            ObjectNode result = objectMapper.createObjectNode();
            result.put("content", content);
            result.put("artifactType", "CODE_PRACTICE");
            result.put("isGrounded", isGrounded);
            result.put("citationCount", citations.size());
            result.set("citations", objectMapper.valueToTree(citations));
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        } catch (Exception e) {
            return content;
        }
    }
}
