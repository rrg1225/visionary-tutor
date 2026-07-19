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
 * PathSpecialistTool - 学习路径规划专家工具
 * 独立实现：RAG检索 → Prompt构建 → LLM生成
 * 实现 SpecialistTool 接口支持动态工具注册
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PathSpecialistTool implements SpecialistTool {

    public static final String TOOL_NAME = "generate_learning_path";

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
        String availableResources = ReActContext.getStringParam(args, "availableResources", "[]");
        String targetDuration = ReActContext.getStringParam(args, "targetDuration", "1周");
        Long learningSessionId = ReActContext.getLongParam(args, "learningSessionId");
        String memoryId = ReActContext.getStringParam(args, "memoryId", ctx.memoryId());

        return generateLearningPath(memoryId, topic, learnerProfile, availableResources, targetDuration, learningSessionId);
    }

    @Tool(name = TOOL_NAME,
          value = "Generates step-by-step learning path with resource sequencing. " +
                       "Use this when: 1) All core resources are generated, 2) Need to organize learning sequence, " +
                       "3) Creating personalized study plan based on profile. " +
                       "Parameters: topic (string), learnerProfile (JSON string), " +
                       "availableResources (array of resource types), targetDuration (string, e.g., '1周'), " +
                       "learningSessionId (long).")
    @Transactional
    public String generateLearningPath(
            @ToolMemoryId String memoryId,
            String topic,
            String learnerProfile,
            String availableResources,
            String targetDuration,
            Long learningSessionId) {

        log.info("[PathTool] Generating path for topic='{}', resources='{}', sessionId={}",
                topic, availableResources, learningSessionId);

        try {
            // 1. RAG 检索
            RagRetrievalResult ragResult = ragRetrievalService.retrieveForTask(
                    AgentTaskType.RESOURCE_GENERATION,
                    topic + " 学习路径规划 学习顺序"
            );
            String ragContext = formatRagContext(ragResult);
            List<RagCitation> citations = ragResult.citations();

            // 2. 构建 Prompt
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(topic, learnerProfile, availableResources,
                    targetDuration != null ? targetDuration : "1周", ragContext);

            // 3. LLM 生成
            String generatedContent;
            if (deepSeekApiClient.isConfigured()) {
                generatedContent = deepSeekApiClient.chat(systemPrompt, userPrompt, false);
            } else {
                generatedContent = buildFallbackContent(topic, availableResources, targetDuration);
            }

            return formatResult(generatedContent);

        } catch (java.net.SocketTimeoutException e) {
            log.warn("[PathTool] Timeout generating learning path for topic='{}': {}", topic, e.getMessage());
            return ToolErrorMessageBuilder.timeoutError(TOOL_NAME, topic);
        } catch (Exception e) {
            log.error("[PathTool] Failed: {}", e.getMessage());
            return ToolErrorMessageBuilder.unknownError(TOOL_NAME, e.getMessage(), topic);
        }
    }

    private String buildSystemPrompt() {
        return """
                你是PathAgent，专门为学生规划个性化学习路径。

                严格规则：
                1. 基于已有资源安排学习顺序，不要规划未生成的资源类型
                2. 考虑学生画像调整路径难度和节奏
                3. 每个步骤明确：学习目标 → 使用资源 → 预计时间 → 自测方式
                4. 使用Markdown格式，步骤卡片式呈现
                5. 为薄弱点预留额外复习时间
                """;
    }

    private String buildUserPrompt(String topic, String learnerProfile, String availableResources,
                                    String targetDuration, String ragContext) {
        return """
                为"%s"规划学习路径

                已有资源类型：%s
                学生画像：%s
                目标学习周期：%s

                参考资料：
                %s

                请输出完整学习路径规划（5-7步，每步含时间、标准、资源）。
                """.formatted(
                topic,
                availableResources != null ? availableResources : "[]",
                learnerProfile != null ? learnerProfile.substring(0, Math.min(learnerProfile.length(), 400)) : "{}",
                targetDuration,
                ragContext.isBlank() ? "无" : ragContext
        );
    }

    private String formatRagContext(RagRetrievalResult result) {
        if (result == null || result.groundedContextBlock().isBlank()) {
            return "";
        }
        return result.groundedContextBlock();
    }

    private String buildFallbackContent(String topic, String availableResources, String targetDuration) {
        String duration = targetDuration != null ? targetDuration : "2周";
        String resources = availableResources != null ? availableResources : "讲义、题库、导图";

        return """
                # %s 学习路径规划

                ## 路径概览
                - 预计用时：%s
                - 核心资源：%s

                ## 学习步骤
                1. 补概念 → 2. 练习 → 3. 项目实践

                > 提示：配置DeepSeek API后可获取基于学生画像的个性化详细路径规划。
                """.formatted(topic, duration, resources);
    }

    private String formatResult(String content) {
        try {
            ObjectNode result = objectMapper.createObjectNode();
            result.put("content", content);
            result.put("artifactType", "LEARNING_PATH");
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        } catch (Exception e) {
            return content;
        }
    }
}
