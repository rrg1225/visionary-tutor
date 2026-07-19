package com.visionary.agent;

import com.visionary.agent.routing.RoutingStrategy;
import com.visionary.dto.AgentInvokeRequest;
import com.visionary.rag.RagRetrievalResult;
import com.visionary.rag.RagRetrievalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Agent 路由网关：策略路由 + Plan-and-Solve 提示词组装 + 分层 RAG 注入（调用大模型前完成）。
 */
@Slf4j
@Component
public class RouterGateway {

    public static final String RAG_ENRICHED_MARKER = "<!-- VISIONARY_RAG_ENRICHED -->";

    private final AgentHandlerRegistry handlerRegistry;
    private final List<RoutingStrategy> routingStrategies;
    private final RagRetrievalService ragRetrievalService;

    public RouterGateway(
            AgentHandlerRegistry handlerRegistry,
            List<RoutingStrategy> routingStrategies,
            RagRetrievalService ragRetrievalService
    ) {
        this.handlerRegistry = handlerRegistry;
        this.routingStrategies = routingStrategies;
        this.ragRetrievalService = ragRetrievalService;
    }

    public AgentResponse<?> dispatch(AgentInvokeRequest request) {
        return dispatch(request, null);
    }

    public AgentResponse<?> dispatch(AgentInvokeRequest request, MultipartFile file) {
        long start = System.currentTimeMillis();
        AgentTaskType requested = request.taskType();

        AgentTaskType taskType = requested != null ? requested : resolveByStrategies(request);
        AgentInvokeRequest enriched = enrichWithPlanAndSolve(request, taskType);

        AgentResponse<?> response = handlerRegistry.getRequired(taskType).process(enriched, file);
        return enrichResponse(response, requested, taskType, start);
    }

    public AgentTaskType resolveRoute(AgentInvokeRequest request) {
        return request.taskType() != null
                ? request.taskType()
                : resolveByStrategies(request);
    }

    /**
     * Plan-and-Solve + ReAct 风格系统指令：先 &lt;thought&gt; 规划知识调用，再输出 Markdown；JSON 必须含 citations [cite: 74]。
     */
    AgentInvokeRequest enrichWithPlanAndSolve(AgentInvokeRequest request, AgentTaskType taskType) {
        if (!requiresGroundedRag(taskType)) {
            return request;
        }

        String query = resolveQuery(request);
        if (query.isBlank()) {
            return request;
        }

        RagRetrievalResult rag = ragRetrievalService.retrieveForTask(taskType, query);
        String planSolvePrompt = buildPlanAndSolvePrompt(taskType, rag);

        // 如果原有 contextPrompt 不为空，则将 RAG 模板追加在原有内容之后，而非覆盖
        String existingContextPrompt = request.contextPrompt();
        String finalContextPrompt;
        if (existingContextPrompt != null && !existingContextPrompt.isBlank()) {
            finalContextPrompt = existingContextPrompt + "\n\n" + planSolvePrompt;
        } else {
            finalContextPrompt = planSolvePrompt;
        }

        return copyRequest(request, finalContextPrompt);
    }

    private String buildPlanAndSolvePrompt(AgentTaskType taskType, RagRetrievalResult rag) {
        String taskLabel = taskType.name();
        String citationInstructionBlock = rag.toCitationInstructionBlock();
        String citationManifest = citationManifest(rag);
        String groundedContext = rag.groundedContextBlock();

        // 使用 StringBuilder 进行安全拼接，避免 String.format 对 % 字符的解析问题
        StringBuilder sb = new StringBuilder();
        sb.append(RAG_ENRICHED_MARKER).append("\n\n");
        sb.append("# Plan-and-Solve 执行协议（必须遵守）\n\n");
        sb.append("你是 VisionaryTutor 的核心教学 Agent，任务类型：").append(taskLabel).append("。\n\n");
        sb.append("## 第一阶段：内部规划（仅输出在 <thought> 标签内）\n");
        sb.append("在 <thought>...</thought> 中完成 ReAct 式推理，写明：\n");
        sb.append("1. 用户问题拆解为哪些子问题；\n");
        sb.append("2. 需要引用下方哪几个知识层（Application / Algorithm / Math）；\n");
        sb.append("3. 准备使用哪些 citationId（若无检索结果则明确写「无可用引用」）。\n\n");
        sb.append("## 第二阶段：面向用户的最终回答\n");
        sb.append("<thought> 结束后，输出清晰的 Markdown 教学内容（公式、步骤、示例）。\n\n");
        sb.append("## 第三阶段：结构化引用（强制）\n");
        sb.append("在 Markdown 之后，单独输出一个 JSON 代码块，格式严格如下：\n");
        sb.append("```json\n");
        sb.append("{\n");
        sb.append("  \"citations\": [\n");
        sb.append("    { \"citationId\": \"cite-1\", \"source\": \"文件名.md\", \"layer\": \"course_layer\" }\n");
        sb.append("  ]\n");
        sb.append("}\n");
        sb.append("```\n");
        sb.append("- citations 只能来自下方「可用引用清单」；无依据时必须是空数组 []。\n");
        sb.append("- 禁止编造未在检索上下文中出现的教材章节或配图路径 [cite: 74]。\n\n");
        sb.append(citationInstructionBlock).append("\n\n");
        sb.append(citationManifest).append("\n\n");
        sb.append("--- Retrieved Knowledge Context (Grounded) ---\n");
        sb.append(groundedContext);

        return sb.toString();
    }

    private static String citationManifest(RagRetrievalResult rag) {
        if (!rag.hasGroundedEvidence()) {
            return "## 可用引用清单\n(空 — 不得虚构引用)";
        }
        StringBuilder sb = new StringBuilder("## 可用引用清单\n");
        rag.citations().forEach(c -> sb.append("- ")
                .append(c.citationId())
                .append(" → ")
                .append(c.source())
                .append(" (")
                .append(c.layer())
                .append(")\n"));
        return sb.toString();
    }

    private boolean requiresGroundedRag(AgentTaskType taskType) {
        return taskType == AgentTaskType.KNOWLEDGE_DIAGNOSIS
                || taskType == AgentTaskType.RESOURCE_GENERATION;
    }

    private AgentTaskType resolveByStrategies(AgentInvokeRequest request) {
        for (RoutingStrategy strategy : routingStrategies) {
            if (strategy.matches(request)) {
                log.debug("RoutingStrategy [{}] selected task: {}",
                        strategy.getClass().getSimpleName(), strategy.getTaskType());
                return strategy.getTaskType();
            }
        }
        return AgentTaskType.RESOURCE_GENERATION;
    }

    private AgentResponse<?> enrichResponse(AgentResponse<?> response,
                                             AgentTaskType requested,
                                             AgentTaskType resolved,
                                             long startTime) {
        AgentResponse.AgentResponseBuilder<?> builder = response.toBuilder();

        if (response.getTaskType() == null) {
            builder.taskType(requested);
        }
        if (response.getResolvedRoute() == null) {
            builder.resolvedRoute(resolved);
        }
        if (response.getLatencyMs() == null) {
            builder.latencyMs(System.currentTimeMillis() - startTime);
        }
        return builder.build();
    }

    private static String resolveQuery(AgentInvokeRequest request) {
        // 优先从 payloadExt 获取 ragQuery
        String ragQuery = request.getExtString("ragQuery");
        if (ragQuery != null && !ragQuery.isBlank()) {
            return ragQuery.trim();
        }
        // 其次从 payloadExt 获取 learnerQuestion
        String learnerQuestion = request.getExtString("learnerQuestion");
        if (learnerQuestion != null && !learnerQuestion.isBlank()) {
            return learnerQuestion.trim();
        }
        // 最后使用 payloadText
        if (request.payloadText() != null && !request.payloadText().isBlank()) {
            return request.payloadText().trim();
        }
        return "";
    }

    private static AgentInvokeRequest copyRequest(AgentInvokeRequest request, String contextPrompt) {
        return new AgentInvokeRequest(
                request.taskType(),
                request.payloadText(),
                contextPrompt,
                request.payloadExt()
        );
    }
}
