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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * VisualizationAgent - generates topic-specific, self-contained local teaching animations.
 */
@Slf4j
@Service
public class VisualizationAgent extends BaseSpecialistAgent {

    private static final String BB_USED_RAG = "VisualizationAgent_usedRAG";
    private static final String BB_REVISION_APPLIED = "VisualizationAgent_revisionApplied";
    private static final String BB_TASK_INPUT = "VisualizationAgent_taskInput";
    private static final String BB_TOPIC = "VisualizationAgent_topic";

    private final ObjectMapper objectMapper;
    private final DeepSeekApiClient deepSeekApiClient;

    public VisualizationAgent(ObjectMapper objectMapper, DeepSeekApiClient deepSeekApiClient,
                                AgentOrchestrationProperties orchestrationProps) {
        this.objectMapper = objectMapper;
        this.deepSeekApiClient = deepSeekApiClient;
        setOrchestrationProperties(orchestrationProps);
    }

    @Override
    public String getRole() {
        return "VisualizationAgent";
    }

    @Override
    public Set<String> getSupportedTools() {
        return Set.of("RAGRetrieveTool", "ArtifactPersistTool");
    }

    @Override
    protected String getSystemPrompt() {
        return """
                你是 VisualizationAgent，专门生成“演示动画＋同步文字注解”的本地教学页面。

                严格规则：
                1. 以模型已有知识和用户主题生成准确动画；RAG 仅作可选补充，实际采用时才保留 citationId
                2. 只输出完全自包含的 HTML + CSS + 原生 JavaScript，不要 Markdown 代码围栏
                3. 禁止外部 CDN、图片、API、视频文件、Manim 渲染或任何云端服务
                4. 动画包含 3—6 个步骤，每一步都有画面变化与同步中文文字注解
                5. 必须提供播放、暂停、上一步、下一步、重置和进度提示
                6. 使用响应式布局，在 360px 宽卡片中也不能横向溢出
                """;
    }

    @Override
    protected String buildRagQuery(AgentTask task, AgentContext context) {
        SharedBlackboard blackboard = context.blackboard();
        String topic = resolveTopic(task, blackboard);

        blackboard.put(BB_TASK_INPUT, task.input());
        blackboard.put(BB_TOPIC, topic);
        blackboard.put(BB_REVISION_APPLIED, Optional.ofNullable(task.input().get("revisionInstruction"))
                .map(Object::toString)
                .filter(s -> !s.isBlank())
                .isPresent());

        return topic + " 教学演示动画 步骤 原理 常见误区";
    }

    @Override
    protected String buildLlmPrompt(AgentTask task, String ragContext, AgentContext context) {
        SharedBlackboard blackboard = context.blackboard();
        String topic = Optional.ofNullable(blackboard.get(BB_TOPIC))
                .map(Object::toString)
                .orElse("教学可视化");

        String vizType = String.valueOf(task.input().getOrDefault("vizType", "PROCESS"));
        String interactivity = String.valueOf(task.input().getOrDefault("interactivity", "INTERACTIVE"));
        String revisionBlock = AgentPromptSupport.revisionBlock(task);

        String profile = context.blackboard().getLearnerProfileSnapshot();
        if (profile == null || profile.isBlank()) {
            profile = String.valueOf(task.input().getOrDefault("learnerProfileSnapshot", "{}"));
        }

        String weakPoints = resolveWeakPoints(task, context.blackboard());
        String evidence = ragContext.isBlank() ? "无" : ragContext;

        return """
                生成“%s”的本地教学演示动画

                类型：%s
                交互级别：%s
                学生画像：%s
                薄弱点：%s

                证据材料：
                %s

                要求：
                1. 用 CSS transform/transition 或 requestAnimationFrame 展示与主题直接相关的变化过程
                2. 设置 3—6 个可逐步播放的画面，每步显示“发生了什么、为什么、易错点”文字注解
                3. 提供播放/暂停、上一步、下一步、重置和进度显示
                4. 在 HTML 注释中写明教学目的和引用 citationId
                5. 只有确实使用假设数据或非真实比例时才标注「示意」，不得仅因未命中 RAG 就降级
                6. 不访问任何外部网络资源

                仅输出完整自包含 HTML。
                """.formatted(
                topic,
                vizType,
                interactivity,
                profile.length() > 300 ? profile.substring(0, 300) : profile,
                weakPoints.isBlank() ? "待观察" : weakPoints,
                evidence
        ) + revisionBlock;
    }

    @Override
    protected String performLlmGeneration(String systemPrompt, String userPrompt, AgentTask task, AgentContext context) {
        if (deepSeekApiClient == null || !deepSeekApiClient.isConfigured()) {
            throw new IllegalStateException("DeepSeek API not configured");
        }
        try {
            String content = deepSeekApiClient.chat(systemPrompt, userPrompt, false);
            return stripCodeFences(content);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected String buildFallbackContent(AgentTask task, String ragContext, AgentContext context) {
        SharedBlackboard blackboard = context.blackboard();
        String topic = Optional.ofNullable(blackboard.get(BB_TOPIC))
                .map(Object::toString)
                .orElse("教学可视化");

        Object vizType = task.input().get("vizType");
        if (vizType != null && !vizType.toString().isBlank()) {
            String interactivity = String.valueOf(task.input().getOrDefault("interactivity", "INTERACTIVE"));
            return toolFallbackTemplate(topic, vizType.toString(), interactivity);
        }

        return evidenceAwareFallback(topic, ragContext);
    }

    @Override
    protected AgentResult buildResult(String generatedContent, List<String> citations, boolean usedRag,
                                      AgentTask task, AgentContext context) {
        SharedBlackboard blackboard = context.blackboard();
        String topic = Optional.ofNullable(blackboard.get(BB_TOPIC))
                .map(Object::toString)
                .orElse("教学可视化");
        boolean revisionApplied = Boolean.TRUE.equals(blackboard.get(BB_REVISION_APPLIED));

        @SuppressWarnings("unchecked")
        Map<String, Object> taskInput = blackboard.get(BB_TASK_INPUT) instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();

        persistArtifact(taskInput, context, topic, generatedContent);

        blackboard.put(BB_USED_RAG, usedRag);

        blackboard.addTrace(new SharedBlackboard.AgentRunTrace(
                getRole(),
                "rag-llm-pipeline",
                "Visualization for " + topic + (revisionApplied ? " revised from critic feedback" : ""),
                Instant.now()
        ));

        return new AgentResult(
                true,
                generatedContent,
                citations,
                Map.of(
                        "artifactType", "VISUALIZATION",
                        "usedRAG", usedRag,
                        "agentLoop", "standard-pipeline",
                        "revisionApplied", revisionApplied
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
        return "教学可视化";
    }

    private String resolveWeakPoints(AgentTask task, SharedBlackboard blackboard) {
        Object fromTask = task.input().get("weakPointsSnapshot");
        if (fromTask != null && !fromTask.toString().isBlank()) {
            return fromTask.toString();
        }
        Object fromBoard = blackboard.get("weakPointsSnapshot");
        return fromBoard != null ? fromBoard.toString() : "";
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

    private String evidenceAwareFallback(String topic, String ragContext) {
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

        return localAnimationHtml(topic, List.of(label1, label2, label3, label4),
                ragContext.isBlank() ? "当前无知识库证据，以下结构仅为学习步骤示意。" : "关键词来自本次检索证据，请结合引用核对具体结论。");
    }

    private String toolFallbackTemplate(String topic, String vizType, String interactivity) {
        String vizTypeDesc = switch (vizType) {
            case "PROCESS" -> "流程图";
            case "ALGORITHM" -> "算法演示";
            case "DATA_COMPARISON" -> "数据对比";
            case "ARCHITECTURE" -> "架构图";
            default -> "综合可视化";
        };
        String interactivityDesc = switch (interactivity) {
            case "STATIC" -> "静态";
            case "INTERACTIVE" -> "可交互";
            case "ANIMATED" -> "动画演示";
            default -> "标准";
        };
        return localAnimationHtml(
                topic,
                List.of("建立问题", vizTypeDesc, "观察变化", "总结自测"),
                "当前为 " + interactivityDesc + " 透明降级模板；配置模型后可生成主题专属动画。"
        );
    }

    private String localAnimationHtml(String topic, List<String> labels, String notice) {
        List<String> safeLabels = labels.stream().map(VisualizationAgent::escapeHtml).toList();
        return """
                <!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><style>
                *{box-sizing:border-box}body{margin:0;padding:16px;background:linear-gradient(145deg,#f0fdfa,#eef2ff);color:#0f172a;font:14px/1.6 system-ui}.wrap{max-width:900px;margin:auto}h1{margin:4px 0 16px;font-size:clamp(20px,4vw,30px)}.eyebrow{color:#0f766e;font-size:10px;font-weight:800;letter-spacing:.14em}.stage{min-height:270px;display:grid;place-items:center;padding:22px;border-radius:18px;background:white;box-shadow:0 14px 32px #0f172a12}.step{display:none;width:min(100%%,560px);text-align:center}.step.active{display:block;animation:in .38s ease}.visual{padding:25px;border:2px solid #14b8a6;border-radius:18px;background:#ccfbf1;font-size:clamp(18px,4vw,26px);font-weight:800}.annotation{margin:14px 0 0;color:#475569}.controls{display:flex;flex-wrap:wrap;align-items:center;gap:8px;margin-top:12px}.controls button{padding:8px 12px;border:1px solid #0d9488;border-radius:9px;background:white;color:#0f766e;cursor:pointer}.controls .primary{background:#0d9488;color:white}.count{margin-left:auto;color:#64748b}.notice{margin-top:12px;padding:9px 11px;border-left:3px solid #f59e0b;background:#fffbeb;color:#92400e;font-size:12px}@keyframes in{from{opacity:0;transform:translateY(12px)}to{opacity:1;transform:none}}
                </style></head><body><main class="wrap"><span class="eyebrow">LOCAL ANIMATED EXPLAINER</span><h1>%s</h1><section class="stage"><article class="step active"><div class="visual">%s</div><p class="annotation">先确定这一部分的定义、输入与学习目标。</p></article><article class="step"><div class="visual">%s</div><p class="annotation">观察它与前一步的依赖关系，不跳过中间条件。</p></article><article class="step"><div class="visual">%s</div><p class="annotation">改变一个条件，检查过程和结果如何变化。</p></article><article class="step"><div class="visual">%s</div><p class="annotation">用自己的语言复述，并通过一道题验证理解。</p></article></section><nav class="controls"><button onclick="move(-1)">上一步</button><button id="play" class="primary" onclick="toggle()">自动播放</button><button onclick="move(1)">下一步</button><button onclick="restart()">重置</button><span id="count" class="count">1 / 4</span></nav><p class="notice">%s</p></main><script>let i=0,t=null;const s=[...document.querySelectorAll('.step')],c=document.getElementById('count'),p=document.getElementById('play');function show(n){i=(n+s.length)%%s.length;s.forEach((x,k)=>x.classList.toggle('active',i===k));c.textContent=(i+1)+' / '+s.length}function move(d){show(i+d)}function toggle(){if(t){clearInterval(t);t=null;p.textContent='自动播放'}else{t=setInterval(()=>move(1),1800);p.textContent='暂停'}}function restart(){if(t){clearInterval(t);t=null;p.textContent='自动播放'}show(0)}</script></body></html>
                """.formatted(
                escapeHtml(topic), safeLabels.get(0), safeLabels.get(1), safeLabels.get(2), safeLabels.get(3), escapeHtml(notice));
    }

    private static String escapeHtml(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
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
        args.put("type", "VISUALIZATION");
        args.put("title", topic + " 演示动画与文字注解");
        args.put("content", content);
        persistTool.execute(args, new ToolContext(context.blackboard(), context.runId(), Map.of()));
    }
}
