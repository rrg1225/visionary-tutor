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

import java.util.Arrays;
import java.util.List;

/**
 * VisualizationSpecialistTool - 可视化生成专家工具
 * 独立实现：RAG检索 → Prompt构建 → LLM生成
 * 实现 SpecialistTool 接口支持动态工具注册
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VisualizationSpecialistTool implements SpecialistTool {

    public static final String TOOL_NAME = "generate_visualization";

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
        String vizType = ReActContext.getStringParam(args, "vizType", "PROCESS");
        String interactivity = ReActContext.getStringParam(args, "interactivity", "INTERACTIVE");
        Long learningSessionId = ReActContext.getLongParam(args, "learningSessionId");
        String memoryId = ReActContext.getStringParam(args, "memoryId", ctx.memoryId());

        return generateVisualization(memoryId, topic, learnerProfile, vizType, interactivity, learningSessionId);
    }

    @Tool(name = TOOL_NAME,
          value = "Generates interactive visualization configs (ECharts, Mermaid diagrams) for intuitive learning. " +
                       "Use this when: 1) Student needs visual understanding of data/process, " +
                       "2) Explaining algorithms with step-by-step animation, 3) Comparing different approaches. " +
                       "Parameters: topic (string), learnerProfile (JSON string), " +
                       "vizType (PROCESS/ALGORITHM/DATA_COMPARISON/ARCHITECTURE), " +
                       "interactivity (STATIC/INTERACTIVE/ANIMATED), learningSessionId (long).")
    @Transactional
    public String generateVisualization(
            @ToolMemoryId String memoryId,
            String topic,
            String learnerProfile,
            String vizType,
            String interactivity,
            Long learningSessionId) {

        log.info("[VizTool] Generating visualization for topic='{}', type='{}', interactivity='{}', sessionId={}",
                topic, vizType, interactivity, learningSessionId);

        try {
            // 1. RAG 检索
            RagRetrievalResult ragResult = ragRetrievalService.retrieveForTask(
                    AgentTaskType.RESOURCE_GENERATION,
                    topic + " 可视化 图表 " + (vizType != null ? vizType : "")
            );
            String ragContext = formatRagContext(ragResult);
            List<RagCitation> citations = ragResult.citations();
            boolean usedRag = !ragContext.isBlank();

            // 2. 构建 Prompt
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(topic, learnerProfile, vizType, interactivity, ragContext);

            // 3. LLM 生成
            String generatedContent;
            if (deepSeekApiClient.isConfigured()) {
                String raw = deepSeekApiClient.chat(systemPrompt, userPrompt, false);
                generatedContent = stripCodeFences(raw);
            } else {
                generatedContent = buildFallbackContent(topic, vizType, interactivity, ragContext);
            }

            return formatResult(generatedContent, citations, usedRag);

        } catch (java.net.SocketTimeoutException e) {
            log.warn("[VizTool] Timeout generating visualization for topic='{}': {}", topic, e.getMessage());
            return ToolErrorMessageBuilder.timeoutError(TOOL_NAME, topic);
        } catch (Exception e) {
            log.error("[VizTool] Failed: {}", e.getMessage());
            return ToolErrorMessageBuilder.unknownError(TOOL_NAME, e.getMessage(), topic);
        }
    }

    private String buildSystemPrompt() {
        return """
                你是VisualizationAgent，专门生成教学可视化图表配置。

                严格规则：
                1. 以模型已有知识和用户主题确保可视化内容准确；RAG 仅作可选补充
                2. 输出可直接使用的配置代码（ECharts option/Mermaid语法）或完整 HTML
                3. 标注每个元素的教育意义
                4. 只输出完整 HTML 片段（含 ECharts 5 CDN），不要 Markdown 代码围栏
                5. xAxis 数据必须与主题相关，禁止写死「概念1/概念2」
                """;
    }

    private String buildUserPrompt(String topic, String learnerProfile, String vizType,
                                    String interactivity, String ragContext) {
        String vType = vizType != null ? vizType : "PROCESS";
        String inter = interactivity != null ? interactivity : "INTERACTIVE";

        return """
                生成"%s"的可视化配置

                类型：%s
                交互级别：%s
                学生画像：%s

                证据材料：
                %s

                要求：
                1. 使用 ECharts 展示与主题直接相关的数据或概念关系
                2. 至少一个可交互元素（tooltip / dataZoom / 按钮切换）
                3. 在 HTML 注释中写明教学目的
                4. 只有确实使用假设数据或非真实比例时才注明「示意」，不得仅因未命中 RAG 就降级

                请输出完整可视化配置（HTML + ECharts 5）。
                """.formatted(
                topic,
                vType,
                inter,
                learnerProfile != null ? learnerProfile.substring(0, Math.min(learnerProfile.length(), 300)) : "{}",
                ragContext.isBlank() ? "无" : ragContext
        );
    }

    private String formatRagContext(RagRetrievalResult result) {
        if (result == null || result.groundedContextBlock().isBlank()) {
            return "";
        }
        return result.groundedContextBlock();
    }

    private String stripCodeFences(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return trimmed;
    }

    private String buildFallbackContent(String topic, String vizType, String interactivity, String ragContext) {
        String vType = vizType != null ? vizType : "PROCESS";
        String inter = interactivity != null ? interactivity : "INTERACTIVE";

        String vizTypeDesc = switch (vType) {
            case "PROCESS" -> "流程图";
            case "ALGORITHM" -> "算法演示";
            case "DATA_COMPARISON" -> "数据对比";
            case "ARCHITECTURE" -> "架构图";
            default -> "综合可视化";
        };
        String interactivityDesc = switch (inter) {
            case "STATIC" -> "静态";
            case "INTERACTIVE" -> "可交互";
            case "ANIMATED" -> "动画演示";
            default -> "标准";
        };

        // 尝试从RAG内容提取一些关键词作为示例数据
        String label1 = topic.length() > 8 ? topic.substring(0, 8) : topic;
        String label2 = "核心机制";
        String label3 = "常见误区";
        String label4 = "应用案例";

        if (!ragContext.isBlank()) {
            String[] tokens = ragContext.replaceAll("[\\r\\n#>*-]", " ")
                    .split("\\s+");
            List<String> words = Arrays.stream(tokens)
                    .filter(w -> w.length() > 1)
                    .limit(4)
                    .toList();
            if (words.size() >= 4) {
                label1 = words.get(0);
                label2 = words.get(1);
                label3 = words.get(2);
                label4 = words.get(3);
            }
        }

        return """
                <!-- 教学可视化：%s（RAG 降级模板，配置 DeepSeek 后可生成主题专属图表） -->
                <div id="viz-container" style="width:100%%;height:420px;"></div>
                <script src="https://cdn.jsdelivr.net/npm/echarts@5.5.0/dist/echarts.min.js"></script>
                <script>
                const chart = echarts.init(document.getElementById('viz-container'));
                chart.setOption({
                  title: { text: '%s 概念掌握对比', left: 'center' },
                  tooltip: { trigger: 'axis' },
                  xAxis: { type: 'category', data: ['%s', '%s', '%s', '%s'] },
                  yAxis: { type: 'value', max: 100, name: '理解度' },
                  series: [{
                    name: '掌握度',
                    type: 'bar',
                    data: [72, 58, 46, 63],
                    itemStyle: { color: '#3b82f6' }
                  }]
                });
                </script>

                <!-- 配置信息：类型=%s, 交互级别=%s -->
                """.formatted(topic, topic, label1, label2, label3, label4, vizTypeDesc, interactivityDesc);
    }

    private String formatResult(String content, List<RagCitation> citations, boolean isGrounded) {
        try {
            ObjectNode result = objectMapper.createObjectNode();
            result.put("content", content);
            result.put("artifactType", "VISUALIZATION");
            result.put("isGrounded", isGrounded);
            result.put("citationCount", citations.size());
            result.set("citations", objectMapper.valueToTree(citations));
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        } catch (Exception e) {
            return content;
        }
    }
}
