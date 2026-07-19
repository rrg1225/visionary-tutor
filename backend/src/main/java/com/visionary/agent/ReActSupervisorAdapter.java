package com.visionary.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.visionary.agent.core.*;
import com.visionary.agent.tools.*;
import com.visionary.client.DeepSeekApiClient;
import com.visionary.config.AgentOrchestrationProperties;
import com.visionary.config.GovernanceProperties;
import com.visionary.config.ReActProperties;
import com.visionary.config.DeepSeekChatModel;
import com.visionary.mcp.McpToolBridge;
import com.visionary.entity.AgentRunStep;
import com.visionary.exception.AiProviderException;
import com.visionary.repository.AgentRunStepRepository;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ReActSupervisorAdapter - 动态工具注册的 ReAct 监督器适配器
 * <p>
 * 特性：
 * 1. 通过 List&lt;SpecialistTool&gt; 注入所有工具，符合开闭原则
 * 2. 在构造器中自动构建 toolRegistry，无需硬编码 switch 语句
 * 3. 通过 SpecialistTool 接口统一调用所有工具
 */
@Slf4j
@Service
public class ReActSupervisorAdapter implements Agent {

    private final DeepSeekChatModel chatLanguageModel;
    private final DeepSeekApiClient deepSeekApiClient;
    private final List<SpecialistTool> toolsList;
    private final Map<String, SpecialistTool> toolRegistry;
    private final ObjectMapper objectMapper;
    private final AgentRunStepRepository stepRepository;
    private final AgentJsonParser agentJsonParser;
    private final AgentOrchestrationProperties orchestrationProps;
    private final ReActProperties reactProps;
    private final GovernanceProperties governanceProperties;
    private final McpToolBridge mcpToolBridge;

    public ReActSupervisorAdapter(
            DeepSeekChatModel chatLanguageModel,
            DeepSeekApiClient deepSeekApiClient,
            List<SpecialistTool> toolsList,
            ObjectMapper objectMapper,
            AgentRunStepRepository stepRepository,
            AgentOrchestrationProperties orchestrationProps,
            ReActProperties reactProps,
            GovernanceProperties governanceProperties,
            McpToolBridge mcpToolBridge
    ) {
        this.chatLanguageModel = chatLanguageModel;
        this.deepSeekApiClient = deepSeekApiClient;
        this.toolsList = toolsList;
        this.objectMapper = objectMapper;
        this.stepRepository = stepRepository;
        this.agentJsonParser = new AgentJsonParser(objectMapper);
        this.orchestrationProps = orchestrationProps;
        this.reactProps = reactProps;
        this.governanceProperties = governanceProperties;
        this.mcpToolBridge = mcpToolBridge;

        // 构建工具注册表：符合开闭原则，新增工具自动注册，无需修改代码
        this.toolRegistry = new HashMap<>();
        for (SpecialistTool tool : toolsList) {
            String toolName = tool.getToolName();
            if (toolRegistry.containsKey(toolName)) {
                log.warn("[ReActAdapter] Tool '{}' is already registered, skipping duplicate", toolName);
            } else {
                toolRegistry.put(toolName, tool);
                log.debug("[ReActAdapter] Registered tool: {}", toolName);
            }
        }
        registerMcpTools();
        log.info("[ReActAdapter] Initialized with {} tools: {}",
                toolRegistry.size(), toolRegistry.keySet());
    }

    private void registerMcpTools() {
        if (mcpToolBridge == null || !mcpToolBridge.isActive()) {
            return;
        }
        for (SpecialistTool mcpTool : mcpToolBridge.getSpecialistTools()) {
            String toolName = mcpTool.getToolName();
            if (toolRegistry.containsKey(toolName)) {
                log.warn("[ReActAdapter] MCP tool '{}' conflicts with existing tool, skipping", toolName);
            } else {
                toolRegistry.put(toolName, mcpTool);
                log.info("[ReActAdapter] Registered MCP tool: {}", toolName);
            }
        }
    }

    private static final int MAX_PARALLEL_TOOLS = 4;
    private static final Pattern CITATION_HANDLE_PATTERN = Pattern.compile(
            "\\bcite-[\\p{IsHan}A-Za-z0-9_.:-]+\\b"
    );

    /**
     * 工具执行超时时间（毫秒），从配置读取，默认90秒
     */
    private long getToolTimeoutMs() {
        return orchestrationProps != null
                ? orchestrationProps.getSpecialistTimeoutSeconds() * 1000L
                : 90000L;
    }

    // 熔断机制配置
    private static final int MAX_REPEATED_FAILED_TOOL_CALLS = 3;
    private static final int CIRCUIT_BREAKER_WINDOW_SIZE = 5;

    public boolean isReActAvailable() {
        return deepSeekApiClient != null && deepSeekApiClient.isConfigured();
    }

    private static final int MAX_SESSION_MEMORY_CACHE_SIZE = 1000;

    @SuppressWarnings("serial")
    private final Map<String, ChatMemory> sessionMemories = Collections.synchronizedMap(new LinkedHashMap<String, ChatMemory>(MAX_SESSION_MEMORY_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ChatMemory> eldest) {
            return size() > MAX_SESSION_MEMORY_CACHE_SIZE;
        }
    });

    private final ExecutorService toolExecutor = Executors.newFixedThreadPool(
            MAX_PARALLEL_TOOLS,
            new ThreadFactory() {
                private final AtomicInteger threadNumber = new AtomicInteger(1);

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "react-tool-executor-" + threadNumber.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            }
    );

    @Override
    public String getRole() {
        return "Supervisor";
    }

    @Override
    public Set<String> getSupportedTools() {
        return Set.of();
    }

    @Override
    public AgentResult execute(AgentTask task, AgentContext context) {
        String runId = context.runId() != null ? context.runId() : UUID.randomUUID().toString();
        SharedBlackboard blackboard = context.blackboard();

        log.info("[ReActAdapter] Starting ReAct workflow, runId={}, taskType={}", runId, task.type());

        // 检查 ReAct 是否可用，如果不可用则降级到本地 Mock 模式
        if (!isReActAvailable()) {
            log.warn("[ReActAdapter] DeepSeek client not configured, falling back to Local Mock Mode");
            return fallbackToMockMode(runId, task, blackboard, "DeepSeek client is not configured");
        }

        try {
            ReActContext reactCtx = buildReActContext(task, blackboard, runId);

            // 从 Blackboard 读取混合架构的动态规划信息（如果有）
            Object hybridPlan = blackboard.get("hybrid_dynamic_execution_plan");
            if (hybridPlan instanceof com.visionary.agent.PlannerAgent.DynamicExecutionPlan plan) {
                log.info("[ReActAdapter] 检测到混合架构动态规划: {} 个任务, 学习风格={}",
                        plan.getTasks().size(), plan.getDetectedLearningStyle());
                reactCtx.setDynamicPlan(plan);
                reactCtx.setRestrictedToolSet(plan.getSelectedAgents());
            }

            ReActResult result = executeReActLoop(reactCtx);
            return convertToLegacyResult(result, runId, blackboard);
        } catch (com.visionary.exception.AgentOrchestrationException e) {
            // 熔断异常必须向上传播，由 MultiAgentResourceService 触发 Legacy 降级
            throw e;
        } catch (AiProviderException e) {
            log.error("[ReActAdapter] AI Provider error, falling back to Local Mock Mode: {}", e.getMessage());
            return fallbackToMockMode(runId, task, blackboard, "AI Provider error: " + e.getMessage());
        } catch (Exception e) {
            log.error("[ReActAdapter] ReAct workflow failed: {}", e.getMessage(), e);
            // 对于其他异常，也尝试降级到 Mock 模式，确保演示不中断
            if (e.getMessage() != null && (e.getMessage().contains("timeout") || e.getMessage().contains("Connection"))) {
                log.warn("[ReActAdapter] Network/timeout error, falling back to Local Mock Mode");
                return fallbackToMockMode(runId, task, blackboard, "Network error: " + e.getMessage());
            }
            return reactUnavailable(runId, e.getMessage());
        }
    }

    /**
     * 降级到本地 Mock 模式 - 当 DeepSeek 不可用时返回预设的静态结果
     * 确保比赛演示时即使断网系统也有完整输出
     */
    private AgentResult fallbackToMockMode(String runId, AgentTask task, SharedBlackboard blackboard, String reason) {
        log.info("[ReActAdapter] Fallback to Local Mock Mode for runId={}", runId);

        String topic = blackboard.getCurrentTopic() != null
                ? blackboard.getCurrentTopic()
                : (String) task.input().getOrDefault("topic", "课程学习");

        // 构建符合格式的 Mock 结果（离线模式下的预设内容）
        String handoutContent = "# " + topic + "\n\n## 核心概念\n\n"
                + "由于网络连接问题，当前展示的是离线模式下的预设内容。\n\n"
                + "## 学习要点\n1. 基本概念理解\n2. 实际应用案例\n3. 常见问题解答\n\n"
                + "> 提示：恢复网络连接后将生成个性化内容。";

        String quizContent = "## 练习题\n\n1. 【单选题】以下哪项是学习的最佳实践？\n   A. 死记硬背\n   B. 理解+实践\n   C. 只看视频\n   D. 只做练习\n   \n   答案：B\n\n2. 【判断题】离线模式下可以正常浏览预设内容。\n   答案：正确\n\n> 提示：恢复网络后将生成针对薄弱点的个性化题目。";

        String mindmapContent = "```mermaid\nmindmap\n  root((" + topic + "))\n    核心概念\n    实际应用\n    常见问题\n    进阶学习\n```\n\n> 提示：在线模式下将生成基于学习风格的个性化导图。";

        List<Map<String, Object>> mockArtifacts = List.of(
                Map.of("type", "HANDOUT", "title", topic + " - 学习讲义", "content", handoutContent, "status", "MOCK_FALLBACK"),
                Map.of("type", "QUIZ", "title", topic + " - 练习题", "content", quizContent, "status", "MOCK_FALLBACK"),
                Map.of("type", "MINDMAP", "title", topic + " - 知识导图", "content", mindmapContent, "status", "MOCK_FALLBACK")
        );

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("runId", runId);
        metadata.put("workflowType", "ReAct");
        metadata.put("workflowState", "MOCK_FALLBACK");
        metadata.put("fallbackReason", reason);
        metadata.put("mockArtifacts", mockArtifacts);
        metadata.put("isMockMode", true);
        metadata.put("topic", topic);

        String output = "离线模式：由于 " + reason + "，系统返回预设的学习资源包。包含：讲义、练习题、知识导图。恢复网络后将生成个性化内容。";

        log.info("[ReActAdapter] Mock mode result prepared for runId={}, artifacts={}", runId, mockArtifacts.size());

        return new AgentResult(true, output, List.of(), metadata, List.of());
    }


    // ============== ReAct 核心实现 (严格 JSON 状态机) ==============

    private ReActContext buildReActContext(AgentTask task, SharedBlackboard blackboard, String runId) {
        String memoryId = runId;
        ChatMemory chatMemory = sessionMemories.computeIfAbsent(memoryId, k ->
            MessageWindowChatMemory.builder()
                .id(k)
                .maxMessages(50)
                .chatMemoryStore(new InMemoryChatMemoryStore())
                .build()
        );

        String topic = blackboard.getCurrentTopic() != null
            ? blackboard.getCurrentTopic()
            : (String) task.input().getOrDefault("topic", "未指定主题");
        String learnerProfile = blackboard.getLearnerProfileSnapshot() != null
            ? blackboard.getLearnerProfileSnapshot()
            : (String) task.input().getOrDefault("learnerProfileSnapshot", "{}");
        String weakPoints = (String) task.input().getOrDefault("weakPointsSnapshot", "[]");
        Long sessionId = task.input().get("learningSessionId") instanceof Long
            ? (Long) task.input().get("learningSessionId")
            : null;

        return ReActContext.builder()
            .runId(runId)
            .memoryId(memoryId)
            .chatMemory(chatMemory)
            .blackboard(blackboard)
            .topic(topic)
            .learnerProfile(learnerProfile)
            .weakPoints(weakPoints)
            .learningSessionId(sessionId)
            .build();
    }

    private ReActResult executeReActLoop(ReActContext ctx) {
        int iteration = 0;
        List<ToolExecutionRecord> toolRecords = new ArrayList<>();
        List<ReActTraceStep> trace = new ArrayList<>();
        List<Map<String, Object>> traceHistory = new ArrayList<>();

        // 熔断检测状态
        List<String> recentToolCalls = new ArrayList<>();
        Map<String, Integer> failedToolAttempts = new HashMap<>();

        // 返修轮次追踪
        int revisionRound = getRevisionRound(ctx);

        String systemPrompt = buildStrictReActSystemPrompt(ctx);
        List<String> conversation = new ArrayList<>();
        conversation.add("User: " + buildInitialUserMessage(ctx).singleText());

        int maxIterations = resolveMaxIterations(ctx);
        while (iteration < maxIterations) {
            iteration++;
            log.info("[ReAct] Iteration {}/{}, session={}", iteration, maxIterations, ctx.getMemoryId());

            try {
                String historyBlock = String.join("\n\n", conversation);
                String llmRawResponse = deepSeekApiClient.chat(systemPrompt, historyBlock, false);

                AgentJsonParser.ReActDecision decision = agentJsonParser.parseReActDecision(
                        llmRawResponse,
                        allowedReActActions(ctx)
                );
                String thought = decision.thought();
                String action = decision.action();
                JsonNode actionInput = decision.actionInput();
                String actionInputStr = actionInput.toString();

                // 记录 Thought + Action 到黑板
                persistReActStep(ctx, iteration, thought, action, actionInputStr, null);

                // 记录完整轨迹到历史列表
                Map<String, Object> traceEntry = new HashMap<>();
                traceEntry.put("iteration", iteration);
                traceEntry.put("thought", thought);
                traceEntry.put("action", action);
                traceEntry.put("actionInput", actionInputStr);
                traceEntry.put("toolName", action);
                traceHistory.add(traceEntry);

                // 更新黑板上的轨迹历史
                ctx.getBlackboard().put("react.trace_history", traceHistory);

                ReActTraceStep step = new ReActTraceStep(
                    iteration, thought, List.of(action), Map.of(action, actionInputStr),
                    null, "action=" + action, action.equalsIgnoreCase("FINISH") ? "COMPLETE" : "CONTINUE"
                );
                trace.add(step);

                if ("FINISH".equalsIgnoreCase(action)) {
                    if (toolRecords.isEmpty()) {
                        log.warn("[ReActCircuitBreaker] FINISH received with no tool calls - potential loop detected");
                        throw new com.visionary.exception.AgentOrchestrationException(
                            com.visionary.exception.AgentOrchestrationException.CircuitBreakerReason.INFINITE_LOOP_DETECTED,
                            ctx.getRunId(),
                            "Model attempted to finish without generating any resources"
                        );
                    }
                    ctx.getBlackboard().put("finalReActDeliverable", actionInput);
                    log.info("[ReAct] FINISH received at iteration {}", iteration);
                    return buildSuccessResult(ctx, actionInput.toString(), toolRecords, trace, iteration);
                }

                // ========== 熔断检测：检查重复调用模式 ==========
                recentToolCalls.add(action);
                if (recentToolCalls.size() > CIRCUIT_BREAKER_WINDOW_SIZE) {
                    recentToolCalls.remove(0);
                }

                if (isToolRepeatedlyFailing(action, recentToolCalls)) {
                    int failCount = failedToolAttempts.getOrDefault(action, 0) + 1;
                    failedToolAttempts.put(action, failCount);

                    if (failCount >= MAX_REPEATED_FAILED_TOOL_CALLS) {
                        log.error("[ReActCircuitBreaker] Tool '{}' failed {} times consecutively - triggering circuit breaker",
                            action, failCount);

                        AgentResult partialResult = buildPartialResultFromRecords(toolRecords, ctx);

                        throw new com.visionary.exception.AgentOrchestrationException(
                            com.visionary.exception.AgentOrchestrationException.CircuitBreakerReason.REPEATED_FAILED_TOOL_CALLS,
                            ctx.getRunId(),
                            iteration,
                            List.of(action + " (failed " + failCount + " times)"),
                            partialResult
                        );
                    }
                }

                // ========== 动态工具调用（符合开闭原则）==========
                String observation = dispatchTool(action, actionInput, ctx);

                // 检查 Observation 是否为错误消息
                boolean isErrorResponse = isErrorObservation(observation);

                toolRecords.add(new ToolExecutionRecord(
                    null, action, observation, !isErrorResponse,
                    List.of(), List.of(), List.of(), List.of())
                );

                if (isErrorResponse) {
                    log.warn("[ReAct] Tool '{}' returned error response: {}", action,
                        observation.length() > 100 ? observation.substring(0, 100) + "..." : observation);
                    failedToolAttempts.put(action, failedToolAttempts.getOrDefault(action, 0) + 1);
                } else {
                    failedToolAttempts.put(action, 0);
                }

                // 追加 Observation 到对话历史
                conversation.add("Assistant: " + llmRawResponse);
                conversation.add("Observation: " + observation);

                // 更新轨迹历史中的 observation
                if (!traceHistory.isEmpty()) {
                    Map<String, Object> lastEntry = traceHistory.get(traceHistory.size() - 1);
                    lastEntry.put("observation", observation);
                    lastEntry.put("success", !isErrorResponse);
                    ctx.getBlackboard().put("react.trace_history", traceHistory);
                }

                // 记录 Observation
                persistReActStep(ctx, iteration, thought, action, actionInputStr, observation);

            } catch (com.visionary.exception.AgentOrchestrationException e) {
                throw e;
            } catch (Exception e) {
                log.error("[ReAct] Iteration {} failed: {}", iteration, e.getMessage());
                ReActTraceStep errorStep = new ReActTraceStep(
                    iteration, "Error during ReAct step", List.of(), Map.of(),
                    e.getMessage(), "error", "REACT_UNAVAILABLE"
                );
                trace.add(errorStep);
                persistReActStep(ctx, iteration, "Error", "ERROR", "{}", e.getMessage());
                return buildErrorResult(ctx, e.getMessage(), toolRecords, trace, iteration);
            }
        }

        // ========== 达到最大迭代次数：触发熔断 ==========
        log.warn("[ReActCircuitBreaker] Maximum iterations ({}) reached - triggering circuit breaker", maxIterations);

        AgentResult partialResult = buildPartialResultFromRecords(toolRecords, ctx);

        throw new com.visionary.exception.AgentOrchestrationException(
            com.visionary.exception.AgentOrchestrationException.CircuitBreakerReason.MAX_ITERATIONS_REACHED,
            ctx.getRunId(),
            iteration,
            failedToolAttempts.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .map(e -> e.getKey() + " (failed " + e.getValue() + " times)")
                .toList(),
            partialResult
        );
    }

    /**
     * 根据配置与 Hybrid 选中的 Agent 数量动态计算迭代上限。
     * 每个 Specialist 至少占用一轮，另预留 Critic 审查与 FINISH 缓冲。
     */
    private int resolveMaxIterations(ReActContext ctx) {
        int configured = reactProps != null ? reactProps.getMaxIterations() : 16;
        if (ctx.getRestrictedToolSet() == null || ctx.getRestrictedToolSet().isEmpty()) {
            return configured;
        }
        long specialistCount = ctx.getRestrictedToolSet().stream()
                .filter(agent -> mapAgentToToolName(agent) != null)
                .distinct()
                .count();
        // +3: Critic 审查、FINISH、LLM 决策缓冲
        int agentDriven = (int) specialistCount + 3;
        int resolved = Math.max(configured, agentDriven);
        log.debug("[ReAct] Resolved max iterations: configured={}, agentDriven={}, final={}",
                configured, agentDriven, resolved);
        return resolved;
    }

    /**
     * 动态工具分发（符合开闭原则，新增工具自动支持，无需修改代码）
     */
    private String dispatchTool(String toolName, JsonNode actionInput, ReActContext ctx) throws Exception {
        // 混合架构：检查工具是否在受限集合中
        if (!isToolAllowed(toolName, ctx)) {
            throw new IllegalArgumentException("Tool '" + toolName + "' is not in the restricted tool set for this execution. " +
                "Allowed tools: " + ctx.getRestrictedToolSet());
        }

        // 从注册表获取工具
        SpecialistTool tool = toolRegistry.get(toolName);
        if (tool == null) {
            throw new IllegalArgumentException("Unknown action: " + toolName + ". " +
                "Available tools: " + toolRegistry.keySet());
        }

        // 构建参数
        ObjectNode args = actionInput.isObject() ? (ObjectNode) actionInput : objectMapper.createObjectNode();

        // 注入上下文默认值
        if (!args.has("memoryId")) args.put("memoryId", ctx.getMemoryId());
        if (!args.has("topic") && ctx.getTopic() != null) args.put("topic", ctx.getTopic());
        if (!args.has("learnerProfile") && ctx.getLearnerProfile() != null) args.put("learnerProfile", ctx.getLearnerProfile());
        if (!args.has("weakPoints") && ctx.getWeakPoints() != null) args.put("weakPoints", ctx.getWeakPoints());
        if (!args.has("learningSessionId") && ctx.getLearningSessionId() != null) args.put("learningSessionId", ctx.getLearningSessionId());

        // 构建 ReActContext
        SpecialistTool.ReActContext toolCtx = new SpecialistTool.ReActContext(
                ctx.getRunId(),
                ctx.getMemoryId(),
                ctx.getTopic(),
                ctx.getLearnerProfile(),
                ctx.getWeakPoints(),
                ctx.getLearningSessionId()
        );

        // 执行工具
        return tool.executeTool(args, toolCtx);
    }

    /**
     * 检查同一工具是否在短时间内被重复调用（可能陷入死循环）
     */
    private boolean isToolRepeatedlyFailing(String action, List<String> recentToolCalls) {
        if (recentToolCalls.size() < 3) return false;

        int sameToolCount = 0;
        for (int i = recentToolCalls.size() - 1; i >= 0; i--) {
            if (recentToolCalls.get(i).equals(action)) {
                sameToolCount++;
            }
        }
        return sameToolCount >= 3;
    }

    /**
     * 检查 Observation 是否为错误响应
     */
    private boolean isErrorObservation(String observation) {
        if (observation == null || observation.isBlank()) return true;

        return observation.contains("\"status\":\"TOOL_FAILED\"") ||
               observation.contains("\"error\":true") ||
               observation.contains("Action failed:") ||
               observation.contains("LLM_TIMEOUT") ||
               observation.contains("RAG_EMPTY") ||
               observation.contains("AGENT_EXECUTION_FAILED");
    }

    /**
     * 检查工具是否被允许执行（混合架构安全控制）
     */
    private boolean isToolAllowed(String toolName, ReActContext ctx) {
        // 如果没有受限工具集，允许所有工具（向后兼容）
        if (ctx.getRestrictedToolSet() == null || ctx.getRestrictedToolSet().isEmpty()) {
            return true;
        }

        // FINISH 总是允许的
        if ("FINISH".equals(toolName)) {
            return true;
        }

        // review_and_critique 总是允许的（质量控制需要）
        if (CriticReviewTool.TOOL_NAME.equals(toolName)) {
            return true;
        }

        // 检查工具名是否在受限集合中
        for (String agent : ctx.getRestrictedToolSet()) {
            String mappedTool = mapAgentToToolName(agent);
            if (toolName.equals(mappedTool)) {
                return true;
            }
        }

        return false;
    }

    private Set<String> allowedReActActions(ReActContext ctx) {
        Set<String> allowed = toolRegistry.keySet().stream()
                .filter(toolName -> isToolAllowed(toolName, ctx))
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        allowed.add("FINISH");
        return Set.copyOf(allowed);
    }

    /**
     * 将 Agent 角色名映射到工具名
     */
    private String mapAgentToToolName(String agentRole) {
        return switch (agentRole) {
            case "DocAgent", "LectureAgent" -> DocSpecialistTool.TOOL_NAME;
            case "QuizAgent" -> QuizSpecialistTool.TOOL_NAME;
            case "MindMapAgent" -> MindMapSpecialistTool.TOOL_NAME;
            case "PathAgent" -> PathSpecialistTool.TOOL_NAME;
            case "CodingAgent" -> CodingSpecialistTool.TOOL_NAME;
            case "ReadingAgent" -> ReadingSpecialistTool.TOOL_NAME;
            case "VisualizationAgent" -> VisualizationSpecialistTool.TOOL_NAME;
            case "CriticAgent" -> CriticReviewTool.TOOL_NAME;
            default -> null;
        };
    }

    /**
     * 从已执行的 Tool 记录构建部分结果（用于降级）
     */
    private AgentResult buildPartialResultFromRecords(List<ToolExecutionRecord> toolRecords, ReActContext ctx) {
        if (toolRecords.isEmpty()) {
            return null;
        }

        long successCount = toolRecords.stream().filter(ToolExecutionRecord::isSuccess).count();
        long failedCount = toolRecords.size() - successCount;

        String summary = String.format(
            "Partial ReAct execution: %d/%d tools succeeded before circuit breaker. " +
            "Topic: %s, Last successful actions: %s",
            successCount, toolRecords.size(), ctx.getTopic(),
            toolRecords.stream()
                .filter(ToolExecutionRecord::isSuccess)
                .map(ToolExecutionRecord::getToolName)
                .distinct()
                .limit(3)
                .collect(java.util.stream.Collectors.joining(", "))
        );

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("partialExecution", true);
        metadata.put("toolsAttempted", toolRecords.size());
        metadata.put("toolsSucceeded", successCount);
        metadata.put("toolsFailed", failedCount);

        return new AgentResult(
            successCount > 0,
            summary,
            List.of(),
            metadata,
            List.of()
        );
    }

    // ============== 系统提示词构建 ==============

    private String buildStrictReActSystemPrompt(ReActContext ctx) {
        String availableTools = buildAvailableToolsList(ctx);

        // 学习风格感知提示
        String learningStyleHint = "";
        if (ctx.getDynamicPlan() != null) {
            String style = ctx.getDynamicPlan().getDetectedLearningStyle();
            learningStyleHint = switch (style.toLowerCase()) {
                case "visual" -> "\n学习风格提示：学生偏好视觉学习，优先调用 visualization、mind_map 类工具。\n";
                case "textual" -> "\n学习风格提示：学生偏好文字学习，优先调用 lecture_handout, reading_materials, quiz 类工具。\n";
                case "practical" -> "\n学习风格提示：学生偏好实践学习，优先调用 coding_practice, learning_path, quiz 类工具。\n";
                default -> "";
            };
        }

        // 获取 CriticAgent 的 reflection_reason（如果有）
        String reflectionReason = getReflectionReason(ctx);
        String reflectionHint = reflectionReason.isEmpty() ? "" :
            "\n【重要：CriticAgent 反思指导】\n" +
            reflectionReason + "\n" +
            "请务必遵循以上反思指导，修正之前的问题。\n";

        // 获取返修轮次信息
        int revisionRound = getRevisionRound(ctx);
        String revisionHint = revisionRound > 0 ?
            "\n【返修状态】这是第 " + revisionRound + " 轮返修（最多 " + governanceProperties.getMaxRevisionRounds() + " 轮）。\n" : "";

        return """
            You are VisionaryTutor ReAct Supervisor (Hybrid Architecture Mode).
            You MUST output EXACTLY one JSON object and NOTHING ELSE. No markdown fences, no extra text, no explanations.

            JSON contract (strict):
            {
              "thought": "Analyze learner profile, weak points, previous observations and decide next step",
              "action": "tool_name or FINISH",
              "action_input": { "param": "value", ... }
            }

            Available actions (use ONLY these exact names):
            %s

            Hybrid Architecture Rules:
            1. Use ONLY the listed available actions - do not invent new tool names
            2. Follow the learner's preferred learning style when selecting tools
            3. Consider task dependencies: PathAgent should be called after content agents complete
            4. CriticAgent should review outputs before finishing
            5. When task is complete and all required artifacts are produced, output action=FINISH

            %s%s%s
            Current topic: %s
            Learner weak points: %s
            """.formatted(availableTools, learningStyleHint, reflectionHint, revisionHint, ctx.getTopic(), ctx.getWeakPoints());
    }

    private String buildAvailableToolsList(ReActContext ctx) {
        List<String> tools = new ArrayList<>();

        // 如果有限制工具集，只列出被授权的工具
        if (ctx.getRestrictedToolSet() != null && !ctx.getRestrictedToolSet().isEmpty()) {
            for (String agent : ctx.getRestrictedToolSet()) {
                String toolName = mapAgentToToolName(agent);
                if (toolName != null && !tools.contains(toolName)) {
                    tools.add(toolName);
                }
            }
        }

        // 默认包含所有工具（向后兼容）
        if (tools.isEmpty()) {
            tools = new ArrayList<>(toolRegistry.keySet());
        }

        // 确保 FINISH 和 review_and_critique 总是被包含
        if (!tools.contains(CriticReviewTool.TOOL_NAME)) {
            tools.add(CriticReviewTool.TOOL_NAME);
        }
        if (!tools.contains("FINISH")) {
            tools.add("FINISH");
        }

        return String.join(", ", tools);
    }

    private String getReflectionReason(ReActContext ctx) {
        if (ctx.getBlackboard() == null) return "";
        Object reason = ctx.getBlackboard().get("critic.reflection_reason");
        return reason != null ? reason.toString() : "";
    }

    private int getRevisionRound(ReActContext ctx) {
        if (ctx.getBlackboard() == null) return 0;
        Object round = ctx.getBlackboard().get("critic.revision_round");
        if (round instanceof Integer) return (Integer) round;
        if (round instanceof String) {
            try {
                return Integer.parseInt((String) round);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private void persistReActStep(ReActContext ctx, int iteration, String thought, String action, String actionInput, String observation) {
        if (stepRepository == null || ctx.getLearningSessionId() == null) return;
        try {
            AgentRunStep step = new AgentRunStep();
            step.setRunId(ctx.getRunId());
            step.setLearningSessionId(ctx.getLearningSessionId());
            step.setAgentName("ReActSupervisor");
            step.setStepOrder(iteration);
            step.setInputSummary(truncate(thought, 1000));
            step.setOutputSummary(observation != null ? truncate(observation, 1000) : truncate(actionInput, 1000));
            step.setCritique(action);
            step.setStatus(observation == null ? "THOUGHT_ACTION" : "OBSERVATION");
            Map<String, Object> audit = new LinkedHashMap<>();
            audit.put("schemaVersion", "agent-audit-v2");
            audit.put("agent", "ReActSupervisor");
            audit.put("thought", thought);
            audit.put("action", action);
            audit.put("action_input", actionInput);
            if (observation != null) audit.put("observation", observation);
            audit.put("inputSchema", Map.of(
                    "type", "object",
                    "required", List.of("learningSessionId", "topic", "learnerProfileSnapshot")
            ));
            audit.put("outputSchema", Map.of(
                    "type", "object",
                    "required", List.of("status", "observation", "evidence")
            ));

            boolean toolSucceeded = observation != null
                    && !"ERROR".equalsIgnoreCase(action)
                    && !isErrorObservation(observation);
            List<Map<String, Object>> toolCalls = observation == null
                    ? List.of()
                    : List.of(Map.of(
                            "name", action == null ? "" : action,
                            "input", truncate(actionInput, 1000),
                            "success", toolSucceeded,
                            "outputPreview", truncate(observation, 600)
                    ));
            audit.put("toolCalls", toolCalls);
            audit.put("ragEvidence", extractCitationHandles(actionInput, observation));
            audit.put("personalizationEvidence", Map.of(
                    "topicPresent", hasStructuredContent(ctx.getTopic()),
                    "profilePresent", hasStructuredContent(ctx.getLearnerProfile()),
                    "weakPointsPresent", hasStructuredContent(ctx.getWeakPoints()),
                    "toolSelectionConstrained", ctx.getRestrictedToolSet() != null
                            && !ctx.getRestrictedToolSet().isEmpty()
            ));
            audit.put("qualitySignals", Map.of(
                    "hasInput", hasStructuredContent(actionInput),
                    "hasOutput", observation != null && !observation.isBlank(),
                    "toolSucceeded", toolSucceeded,
                    "fallback", containsIgnoreCase(action, "fallback")
                            || containsIgnoreCase(observation, "fallback")
                            || containsIgnoreCase(observation, "degraded")
            ));
            audit.put("revisionDiff", getRevisionRound(ctx) > 0 ? getReflectionReason(ctx) : "");
            step.setAuditTraceJson(objectMapper.writeValueAsString(audit));
            stepRepository.save(step);
        } catch (Exception e) {
            log.warn("[ReAct] Failed to persist AgentRunStep: {}", e.getMessage());
        }
    }

    private List<String> extractCitationHandles(String... values) {
        Set<String> handles = new LinkedHashSet<>();
        if (values == null) {
            return List.of();
        }
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            Matcher matcher = CITATION_HANDLE_PATTERN.matcher(value);
            while (matcher.find()) {
                handles.add(matcher.group());
            }
        }
        return List.copyOf(handles);
    }

    private boolean hasStructuredContent(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.trim();
        if ("{}".equals(normalized) || "[]".equals(normalized) || "null".equalsIgnoreCase(normalized)) {
            return false;
        }
        try {
            JsonNode node = objectMapper.readTree(normalized);
            return node != null && !node.isNull() && !node.isMissingNode()
                    && (!node.isContainerNode() || node.size() > 0);
        } catch (Exception ignored) {
            return true;
        }
    }

    private static boolean containsIgnoreCase(String value, String needle) {
        return value != null && needle != null
                && value.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    // ============== 辅助方法 ==============

    private SystemMessage buildReActSystemMessage(ReActContext ctx) {
        return SystemMessage.from("Legacy system message - not used in strict JSON ReAct mode");
    }

    private UserMessage buildInitialUserMessage(ReActContext ctx) {
        String prompt = """
            Learner request: %s
            Learner profile: %s
            Weak points: %s
            Learning session ID: %s
            """.formatted(ctx.getTopic(), truncate(ctx.getLearnerProfile(), 500), ctx.getWeakPoints(), ctx.getLearningSessionId());
        return UserMessage.from(prompt);
    }

    private AgentResult convertToLegacyResult(ReActResult reactResult, String runId, SharedBlackboard blackboard) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("runId", runId);
        metadata.put("workflowType", "ReAct");
        metadata.put("iterations", reactResult.getIterations());
        metadata.put("toolsExecuted", reactResult.getToolRecords().size());
        metadata.put("toolsExecutedNames", reactResult.getToolRecords().stream()
            .map(ToolExecutionRecord::getToolName)
            .toList());
        metadata.put("reactTrace", reactResult.getTrace());
        metadata.put("success", reactResult.isSuccess());
        metadata.put("workflowState", reactResult.isSuccess() ? "COMPLETED" : "REACT_UNAVAILABLE");
        if (reactResult.getError() != null) metadata.put("error", reactResult.getError());
        if (mcpToolBridge != null && mcpToolBridge.isActive()) {
            metadata.put("mcpEnabled", true);
            metadata.put("mcpToolCount", mcpToolBridge.getSpecialistTools().size());
            metadata.put("mcpTools", mcpToolBridge.getSpecialistTools().stream()
                    .map(SpecialistTool::getToolName).toList());
        }

        return new AgentResult(
            reactResult.isSuccess(),
            reactResult.getFinalOutput() != null ? reactResult.getFinalOutput() : reactResult.getError(),
            List.of(),
            metadata,
            List.of()
        );
    }

    private ReActResult buildSuccessResult(ReActContext ctx, String finalOutput, List<ToolExecutionRecord> records, List<ReActTraceStep> trace, int iterations) {
        return ReActResult.builder().success(true).complete(true).finalOutput(finalOutput).toolRecords(records).trace(trace).iterations(iterations).build();
    }

    private ReActResult buildErrorResult(ReActContext ctx, String error, List<ToolExecutionRecord> records, List<ReActTraceStep> trace, int iteration) {
        return ReActResult.builder().success(false).complete(false).error(error).toolRecords(records).trace(trace).iterations(iteration).build();
    }

    private String truncate(String str, int maxLength) {
        if (str == null || str.length() <= maxLength) return str;
        return str.substring(0, maxLength) + "...";
    }

    private AgentResult reactUnavailable(String runId, String reason) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("runId", runId);
        metadata.put("workflowType", "ReAct");
        metadata.put("workflowState", "REACT_UNAVAILABLE");
        metadata.put("error", reason);
        return new AgentResult(false, "ReAct unavailable", List.of(), metadata, List.of());
    }

    // ============== 内部类定义 ==============

    @lombok.Data @lombok.Builder
    private static class ReActContext {
        private String runId; private String memoryId; private ChatMemory chatMemory;
        private SharedBlackboard blackboard; private String topic; private String learnerProfile;
        private String weakPoints; private Long learningSessionId;

        // 混合架构扩展字段
        private com.visionary.agent.PlannerAgent.DynamicExecutionPlan dynamicPlan;
        private List<String> restrictedToolSet;
    }

    @lombok.Data @lombok.Builder
    private static class ReActResult {
        private boolean success; private boolean complete; private String finalOutput; private String error;
        private List<ToolExecutionRecord> toolRecords; private List<ReActTraceStep> trace; private int iterations;
    }

    public record ReActTraceStep(
        int iteration, String thought, List<String> selectedTools, Map<String, String> toolArguments,
        String observationSummary, String blackboardDelta, String completionDecision
    ) {}

    @lombok.Data @lombok.AllArgsConstructor
    private static class ToolExecutionRecord {
        private String requestId; private String toolName; private String result; private boolean success;
        private List<String> coveredKnowledgePoints; private List<String> issues;
        private List<String> prerequisiteResources; private List<String> suggestedNextAgents;
    }

    /**
     * 优雅停机：在 Spring 容器关闭时关闭线程池
     */
    @PreDestroy
    public void shutdown() {
        log.info("[ReActAdapter] Shutting down tool executor...");
        toolExecutor.shutdown();
        try {
            if (!toolExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                toolExecutor.shutdownNow();
                if (!toolExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                    log.error("[ReActAdapter] Tool executor did not terminate");
                }
            }
        } catch (InterruptedException e) {
            toolExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("[ReActAdapter] Tool executor shutdown complete");
    }
}
