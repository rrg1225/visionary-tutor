package com.visionary.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.client.DeepSeekApiClient;
import com.visionary.dto.GroundingMetrics;
import com.visionary.dto.ResourceCard;
import com.visionary.dto.ResourceGenerationProgressEvent;
import com.visionary.dto.ResourceGenerationRequest;
import com.visionary.dto.ResourceGenerationResponse;
import com.visionary.dto.TutoringMultimodalRequest;
import com.visionary.dto.TutoringMultimodalResponse;
import com.visionary.entity.GeneratedArtifact;
import com.visionary.entity.GeneratedArtifact.ArtifactType;
import com.visionary.entity.LearningSession;
import com.visionary.config.GovernanceProperties;
import com.visionary.governance.CompositeScoreCalculator;
import com.visionary.governance.GovernanceBestSolutionTracker;
import com.visionary.governance.GovernanceCircuitBreaker.BreakerDecision;
import com.visionary.rag.CitationValidator;
import com.visionary.rag.GroundingEvaluationEngine;
import com.visionary.rag.RagCitation;
import com.visionary.rag.RagRetrievalResult;
import com.visionary.rag.RagRetrievalService;
import com.visionary.os.PublishGate;
import com.visionary.repository.GeneratedArtifactRepository;
import com.visionary.resourcegeneration.domain.GenerationState;
import com.visionary.resourcegeneration.domain.OrchestrationMode;
import com.visionary.resourcegeneration.application.CriticReviewDecision;
import com.visionary.resourcegeneration.application.CriticReviewService;
import com.visionary.resourcegeneration.infrastructure.GenerationTraceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class LegacyGenerationEngine {

    private static final ThreadLocal<ResourceGenerationProgressListener> ACTIVE_PROGRESS_LISTENER = new ThreadLocal<>();

    private static final List<ArtifactType> DEFAULT_TYPES = List.of(
            ArtifactType.HANDOUT,
            ArtifactType.QUIZ,
            ArtifactType.MINDMAP,
            ArtifactType.LEARNING_PATH,
            ArtifactType.CODE_PRACTICE,
            ArtifactType.EXTENDED_READING,
            ArtifactType.VISUALIZATION
    );

    private final DeepSeekApiClient deepSeekApiClient;
    private final RagRetrievalService ragRetrievalService;
    private final CitationValidator citationValidator;
    private final PublishGate publishGate;
    private final GeneratedArtifactRepository artifactRepository;
    private final ObjectMapper objectMapper;
    @Qualifier("activeSupervisorAgent")
    private final com.visionary.agent.core.Agent supervisorAgent;
    private final com.visionary.agent.core.MessageBus messageBus; // 新增
    private final com.visionary.agent.tool.ProfileMergeTool profileMergeTool;
    private final AgentDispatcher agentDispatcher;
    private final PersistenceManager persistenceManager;
    private final GovernanceTraceService governanceTraceService;
    private final GovernanceProperties governanceProperties;
    private final CompositeScoreCalculator compositeScoreCalculator;
    private final LocalMockService localMockService;
    private final GenerationFallbackService generationFallbackService;
    private final GovernanceQualityGateService governanceQualityGateService;
    // ReAct 工具集 - 用于混合架构动态装配
    private final com.visionary.agent.tools.DocSpecialistTool docSpecialistTool;
    private final com.visionary.agent.tools.QuizSpecialistTool quizSpecialistTool;
    private final com.visionary.agent.tools.MindMapSpecialistTool mindMapSpecialistTool;
    private final com.visionary.agent.tools.ReadingSpecialistTool readingSpecialistTool;
    private final com.visionary.agent.tools.CodingSpecialistTool codingSpecialistTool;
    private final com.visionary.agent.tools.PathSpecialistTool pathSpecialistTool;
    private final com.visionary.agent.tools.VisualizationSpecialistTool visualizationSpecialistTool;
    private final com.visionary.agent.tools.CriticReviewTool criticReviewTool;
    // 混合架构 PlannerAgent - 负责动态规划学习风格感知的任务清单
    private final com.visionary.agent.PlannerAgent plannerAgent;
    private final GroundingEvaluationEngine groundingEvaluationEngine;
    private final ResourceCardMapper resourceCardMapper;
    private final GenerationTraceService generationTraceService;
    private final CriticReviewService criticReviewService;

    @Value("${agent.mode:react}")
    private String agentMode;

    @Transactional(readOnly = true)
    public List<GeneratedArtifact> listArtifacts(Long learningSessionId) {
        return persistenceManager.listVisibleArtifacts(learningSessionId);
    }

    @Transactional(readOnly = true)
    public List<ResourceCard> listResourceCards(Long learningSessionId) {
        return resourceCardMapper.toCards(persistenceManager.listVisibleArtifacts(learningSessionId));
    }

    private boolean isVisibleToLearner(GeneratedArtifact artifact) {
        String publishStatus = artifact.getPublishStatus();
        return publishStatus == null
                || publishStatus.isBlank()
                || !"BLOCKED".equalsIgnoreCase(publishStatus);
    }

    private RagRetrievalResult retrieveRagForTask(com.visionary.agent.AgentTaskType taskType, String query) {
        if (ragRetrievalService == null) {
            return RagRetrievalResult.empty();
        }
        try {
            RagRetrievalResult result = ragRetrievalService.retrieveForTask(taskType, query);
            return result != null ? result : RagRetrievalResult.empty();
        } catch (Exception e) {
            log.warn("RAG retrieval failed, using empty evidence: taskType={}, query={}, error={}",
                    taskType, truncate(query, 80), e.getMessage());
            return RagRetrievalResult.empty();
        }
    }

    private CitationValidator.ValidationResult validateContent(String content, RagRetrievalResult rag) {
        if (citationValidator == null) {
            return new CitationValidator.ValidationResult(
                    "NO_VALIDATOR",
                    "引用校验器不可用，已进入演示降级路径"
            );
        }
        try {
            CitationValidator.ValidationResult validation = citationValidator.validate(content, rag);
            return validation != null
                    ? validation
                    : new CitationValidator.ValidationResult("NO_EVIDENCE", "引用校验器未返回结果，按无证据处理");
        } catch (Exception e) {
            log.warn("Citation validation failed, using fallback verdict: {}", e.getMessage());
            return new CitationValidator.ValidationResult("VALIDATION_FAILED", "引用校验失败：" + e.getMessage());
        }
    }

    private GeneratedArtifact saveAndIndexArtifact(GeneratedArtifact artifact) {
        return persistenceManager.saveAndIndexArtifact(artifact);
    }

    @Transactional
    public ResourceGenerationResponse generate(ResourceGenerationRequest request) {
        return generate(request, null);
    }

    @Transactional
    public ResourceGenerationResponse generate(ResourceGenerationRequest request, ResourceGenerationProgressListener listener) {
        return generateWithStrategy(request, listener, defaultOrchestrationMode());
    }

    @Transactional
    public ResourceGenerationResponse generateWithStrategy(
            ResourceGenerationRequest request,
            ResourceGenerationProgressListener listener,
            OrchestrationMode orchestrationMode
    ) {
        // 使用try-with-resources风格的嵌套结构，确保ThreadLocal总是被清理
        class ProgressListenerHolder implements AutoCloseable {
            @Override
            public void close() {
                ACTIVE_PROGRESS_LISTENER.remove();
            }
        }

        try (ProgressListenerHolder holder = new ProgressListenerHolder()) {
            ACTIVE_PROGRESS_LISTENER.set(listener);
            try {
                return generateInternal(request, listener, orchestrationMode);
            } catch (Exception e) {
                log.error("[MultiAgentResourceService] Generation failed, attempting legacy recovery: {}", e.getMessage(), e);
                return recoverGeneration(request, listener, e);
            }
        } catch (OutOfMemoryError | StackOverflowError e) {
            // Error类型异常发生时，AutoCloseable可能无法执行
            // 强制清理ThreadLocal
            ACTIVE_PROGRESS_LISTENER.remove();
            log.error("[MultiAgentResourceService] Critical error during generation: {}", e.getMessage(), e);
            throw e;
        }
    }

    private OrchestrationMode defaultOrchestrationMode() {
        return localMockService.isEnabled()
                ? OrchestrationMode.DEMO
                : OrchestrationMode.fromConfiguration(agentMode);
    }

    private ResourceGenerationResponse recoverGeneration(
            ResourceGenerationRequest request,
            ResourceGenerationProgressListener listener,
            Exception cause
    ) {
        LearningSession session = persistenceManager.requireSession(request.learningSessionId());
        String runId = UUID.randomUUID().toString();
        String topic = firstNonBlank(request.topic(), session.getTopic(), "课程个性化学习");
        if (localMockService.isEnabled()) {
            return localMockService.generateResources(runId, session, topic, request);
        }
        try {
            AgentBlackboard blackboard = new AgentBlackboard(
                    "Emergency fallback plan for " + topic,
                    new ArrayList<>()
            );
            return executeLegacyGenerationPath(
                    request,
                    session,
                    runId,
                    topic,
                    blackboard,
                    listener,
                    "Top-level recovery: " + cause.getMessage()
            );
        } catch (Exception legacyFailure) {
            log.error("[MultiAgentResourceService] Legacy recovery failed; demo fixtures are disabled outside demo mode: {}",
                    legacyFailure.getMessage(), legacyFailure);
            throw new IllegalStateException(
                    "Resource generation unavailable after primary and legacy pipelines failed. "
                            + "Demo fixtures were not used because demo mode is disabled.",
                    legacyFailure
            );
        }
    }

    private ResourceGenerationResponse generateInternal(
            ResourceGenerationRequest request,
            ResourceGenerationProgressListener listener,
            OrchestrationMode mode
    ) {
        LearningSession session = persistenceManager.requireSession(request.learningSessionId());
        String runId = UUID.randomUUID().toString();
        String topic = firstNonBlank(request.topic(), session.getTopic(), "课程个性化学习");
        String orchestrationMode = mode.name();
        generationTraceService.start(runId, session.getId(), orchestrationMode, "Resource generation accepted");
        generationTraceService.safeTransition(runId, GenerationState.PLANNING,
                "PlannerAgent", orchestrationMode, "Resolve learner profile and resource plan");
        generationTraceService.safeTransition(runId, GenerationState.RETRIEVING,
                "RagRetrievalService", orchestrationMode, "Retrieve grounded course evidence");
        generationTraceService.safeTransition(runId, GenerationState.GENERATING,
                "AgentOrchestrator", orchestrationMode, "Execute selected specialist agents");
        try {
            ResourceGenerationResponse response = generatePipeline(request, listener, session, runId, topic, mode);
            generationTraceService.safeTransition(runId, GenerationState.CRITIQUING,
                    "CriticAgent", orchestrationMode, "Validate factuality, safety and resource completeness");
            if (response.artifacts() != null && response.artifacts().stream().anyMatch(this::isDegradedArtifact)) {
                generationTraceService.safeTransition(runId, GenerationState.DEGRADED,
                        "GenerationFallbackPolicy", orchestrationMode, "One or more artifacts used a degraded path");
            }
            generationTraceService.safeTransition(runId, GenerationState.PERSISTING,
                    "ResourcePersistenceService", orchestrationMode, "Persist generated resources and provenance");
            generationTraceService.safeTransition(runId, GenerationState.SUCCEEDED,
                    "ResourceGenerationUseCase", orchestrationMode, "Resource generation completed");
            return response;
        } catch (RuntimeException exception) {
            generationTraceService.safeTransition(runId, GenerationState.FAILED,
                    "ResourceGenerationUseCase", orchestrationMode, exception.getMessage());
            throw exception;
        }
    }

    private boolean isDegradedArtifact(GeneratedArtifact artifact) {
        return artifact != null && ("DEGRADED".equalsIgnoreCase(artifact.getPublishStatus())
                || (artifact.getContentJson() != null && artifact.getContentJson().contains("\"degraded\":true")));
    }

    private ResourceGenerationResponse generatePipeline(
            ResourceGenerationRequest request,
            ResourceGenerationProgressListener listener,
            LearningSession session,
            String runId,
            String topic,
            OrchestrationMode mode
    ) {
        if (mode == OrchestrationMode.DEMO) {
            if (!localMockService.isEnabled()) {
                throw new IllegalStateException("Demo orchestration requested while demo mode is disabled");
            }
            return localMockService.generateResources(runId, session, topic, request);
        }

        // Workflow is deterministic and never enters the autonomous ReAct tool loop.
        if (mode == OrchestrationMode.WORKFLOW) {
            String typesLabel = resolveTypes(request.resourceTypes()).stream()
                    .map(ArtifactType::name)
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("ALL");
            AgentBlackboard incrementalBoard = new AgentBlackboard(plan(topic, request, session), new ArrayList<>());
            saveStep(runId, session.getId(), "PlannerAgent", 1, topic, incrementalBoard.plan(),
                    "Deterministic workflow: " + typesLabel);
            emitProgress(listener, runId, "workflow", "LegacyPipeline", 1,
                    "确定性工作流生成资源", typesLabel, 10);
            return executeLegacyGenerationPath(request, session, runId, topic, incrementalBoard, listener, null);
        }

        String fallbackReason = supervisorAgent == null ? "SupervisorAgent unavailable; using legacy pipeline." : null;

        // ==================== 混合架构：动态规划阶段 ====================
        // Step 1: 调用 PlannerAgent 获取动态执行计划（根据 LearnerProfile）
        com.visionary.agent.PlannerAgent.DynamicExecutionPlan dynamicPlan = null;
        boolean useHybridArchitecture = true;

        try {
            dynamicPlan = executeHybridPlannerPhase(runId, session, topic, request, listener);
            if (dynamicPlan == null || !dynamicPlan.isValid()) {
                log.warn("[HybridArchitecture] Planner 返回无效计划，回退到全量并行模式");
                useHybridArchitecture = false;
                fallbackReason = "Planner returned invalid plan; falling back to full parallel mode";
            } else {
                log.info("[HybridArchitecture] 动态规划成功: 学习风格={}, 选中 {} 个 Agents: {}",
                        dynamicPlan.getDetectedLearningStyle(),
                        dynamicPlan.getSelectedAgents().size(),
                        String.join(", ", dynamicPlan.getSelectedAgents()));
                emitProgress(listener, runId, "workflow", "PlannerAgent(Hybrid)", 1,
                        "动态规划完成", "学习风格: " + dynamicPlan.getDetectedLearningStyle() +
                                ", 选中: " + String.join(", ", dynamicPlan.getSelectedAgents()), 8);
            }
        } catch (Exception e) {
            log.warn("[HybridArchitecture] Planner 阶段失败: {}，回退到全量并行模式", e.getMessage());
            useHybridArchitecture = false;
            fallbackReason = "Planner failed: " + e.getMessage();
            emitProgress(listener, runId, "degraded", "PlannerAgent", 1,
                    "动态规划失败，回退到全量模式", fallbackReason, 8);
        }

        // 构建Blackboard用于Agent间共享状态
        String planSummary = dynamicPlan != null ? dynamicPlan.getPlanSummary() : plan(topic, request, session);
        AgentBlackboard blackboard = new AgentBlackboard(planSummary, new ArrayList<>());
        saveStep(runId, session.getId(), "PlannerAgent", 1, topic, blackboard.plan(),
                useHybridArchitecture ? "动态规划完成，选中 " + dynamicPlan.getSelectedAgents().size() + " 个 Agents" : "已拆解资源任务并分派给专门 Agent");
        emitProgress(listener, runId, "workflow", "PlannerAgent", 1, "学习任务已拆解", blackboard.plan(), 8);

        // ==================== 混合架构：工具注册表动态装配 ====================
        // 根据 Planner 的选中 Agents，按需装配受限 ToolRegistry
        Map<String, com.visionary.agent.core.Tool> tools;
        if (useHybridArchitecture && dynamicPlan != null) {
            tools = buildRestrictedToolRegistry(dynamicPlan.getSelectedAgents());
            log.info("[HybridArchitecture] 动态装配 ToolRegistry: {} 个工具对应 {} 个 Agents",
                    tools.size(), dynamicPlan.getSelectedAgents().size());
            // 将动态计划存入 Blackboard，供后续使用
            blackboard = new AgentBlackboard(planSummary + "\n[HybridPlan] Selected agents: " + String.join(", ", dynamicPlan.getSelectedAgents()),
                    new ArrayList<>());
        } else {
            tools = buildToolRegistry(); // 全量工具
        }

        // === 可审计多角色编排主路径 ===
        if (agentDispatcher.isAvailable()) {
            try {
                com.visionary.agent.core.MessageBus bus = messageBus != null
                        ? messageBus
                        : new com.visionary.agent.impl.InMemoryMessageBus();

                com.visionary.agent.core.SharedBlackboard sharedBlackboard = convertToSharedBlackboard(blackboard, request);
                sharedBlackboard.setRunId(runId);

                // 将动态规划存入 SharedBlackboard，供下游使用（键名与 ReActSupervisorAdapter 匹配）
                if (dynamicPlan != null) {
                    sharedBlackboard.put("hybrid_dynamic_execution_plan", dynamicPlan);
                    sharedBlackboard.put("hybrid_selected_agents", dynamicPlan.getSelectedAgents());
                    sharedBlackboard.put("hybrid_learning_style", dynamicPlan.getDetectedLearningStyle());
                }

                Map<String, Object> metadata = new HashMap<>();
                if (listener != null) {
                    metadata.put("progressListener", listener);
                }

                com.visionary.agent.core.AgentContext ctx = new com.visionary.agent.core.AgentContext(
                        sharedBlackboard,
                        tools,  // 使用动态装配的受限 ToolRegistry
                        bus,
                        runId,
                        metadata
                );

                // 构建Agent任务 - 根据混合架构模式选择参与 Agents
                Map<String, Object> taskInput = new HashMap<>();
                taskInput.put("learningSessionId", session.getId());
                taskInput.put("topic", topic);
                taskInput.put("learnerProfileSnapshot", request.learnerProfileSnapshot());
                taskInput.put("weakPointsSnapshot", request.weakPointsSnapshot());
                taskInput.put("emotionSnapshot", request.emotionSnapshot());
                taskInput.put("userQuestion", request.topic() != null ? request.topic() : "");
                if (hasExplicitResourceTypes(request)) {
                    taskInput.put("resourceTypes", request.resourceTypes());
                }

                // 根据动态规划选择参与的 Agents
                List<String> participatingAgents;
                if (hasExplicitResourceTypes(request)) {
                    // A page-scoped generation request is authoritative. Dynamic
                    // planning may optimize how a specialist works, but it must
                    // never add unrelated resource specialists.
                    participatingAgents = new ArrayList<>();
                } else if (useHybridArchitecture && dynamicPlan != null) {
                    participatingAgents = new ArrayList<>(dynamicPlan.getSelectedAgents());
                } else {
                    participatingAgents = new ArrayList<>(List.of("PlannerAgent", "DocAgent", "QuizAgent", "MindMapAgent",
                            "ReadingAgent", "PathAgent", "CodingAgent", "VisualizationAgent"));
                }
                participatingAgents = mergeRequiredAgents(participatingAgents, request);

                com.visionary.agent.core.AgentTask task = new com.visionary.agent.core.AgentTask(
                        runId,
                        com.visionary.agent.AgentTaskType.RESOURCE_GENERATION.name(),
                        taskInput,
                        participatingAgents
                );

                // 执行可审计的多角色编排工作流
                emitProgress(listener, runId, "workflow", "Supervisor", 2,
                        useHybridArchitecture ? "混合架构多智能体协作启动 (" + participatingAgents.size() + " agents)" : "多智能体并行协作启动",
                        topic, 12);

                // 混合架构：传递选中的 Agents 列表
                com.visionary.agent.core.AgentResult result = agentDispatcher.dispatchResourceGeneration(
                        runId, session.getId(), topic, request, listener, participatingAgents);

                if (!result.success() && mode == OrchestrationMode.REACT) {
                    String reactError = String.valueOf(result.metadata().getOrDefault(
                            "error", result.metadata().getOrDefault("workflowState", "ReAct workflow failed")));
                    fallbackReason = "ReAct workflow failed: " + reactError;
                    log.warn("[ReActFallback] ReAct returned success=false ({}), falling back to legacy pipeline", reactError);
                    saveStep(runId, session.getId(), "LegacyPipeline", 90,
                            "ReAct recovery", fallbackReason, "切换至稳定模型生成链路");
                    emitProgress(listener, runId, "recovery", "LegacyPipeline", 90,
                            "主编排未完成，正在切换至稳定模型生成链路", fallbackReason, 90);
                    return executeLegacyGenerationPath(request, session, runId, topic, blackboard, listener, fallbackReason);
                }

                emitProgress(listener, runId, "workflow", "Supervisor", 3, "Specialist 完成，进入 Critic 审查", topic, 58);

                // 工作流完成后，四层 Critic 返修 + 视频任务 + 安全元数据
                emitProgress(listener, runId, "workflow", "CriticAgent", 4, "逐类资源审查与返修", topic, 65);
                boolean enforceCoverage = hasExplicitResourceTypes(request)
                        || !"ReAct".equalsIgnoreCase(String.valueOf(result.metadata().get("workflowType")));
                String reviewSummary = finalizeSupervisorArtifacts(runId, session.getId(), topic, request, blackboard, enforceCoverage);
                emitProgress(listener, runId, "workflow", "ReviewAgent", 5, "全部资源审查完成", reviewSummary, 95);

                // 工作流完成后，从数据库读取生成的资源
                List<GeneratedArtifact> artifacts = persistenceManager.findRunArtifacts(runId);
                if (hasExplicitResourceTypes(request)) {
                    List<ArtifactType> requestedTypes = resolveTypes(request.resourceTypes());
                    artifacts = artifacts.stream()
                            .filter(artifact -> requestedTypes.contains(artifact.getArtifactType()))
                            .toList();
                }

                if (artifacts.isEmpty() && mode == OrchestrationMode.REACT) {
                    fallbackReason = "ReAct completed without persisted artifacts";
                    log.warn("[ReActFallback] ReAct finished but 0 artifacts persisted, falling back to legacy pipeline");
                    saveStep(runId, session.getId(), "LegacyPipeline", 90,
                            "ReAct empty-artifact recovery", fallbackReason, "切换至稳定模型生成链路");
                    emitProgress(listener, runId, "recovery", "LegacyPipeline", 90,
                            "专用 Agent 尚未保存资源，正在由稳定模型链路补齐", fallbackReason, 90);
                    return executeLegacyGenerationPath(request, session, runId, topic, blackboard, listener, fallbackReason);
                }

                // 如果有视频任务，启动异步轮询
                artifacts.stream()
                        .filter(a -> a.getMediaTaskId() != null && !"SUCCEEDED".equals(a.getMediaStatus()))
                        .forEach(a -> log.info("Video generation pending for artifact: {}", a.getId()));

                session.setCurrentPhase(LearningSession.LearningPhase.RESOURCE_GENERATION);
                persistenceManager.markResourceGenerationPhase(session);

                emitProgress(listener, runId, "workflow", "ReviewAgent", 99, "资源生成完成", reviewSummary, 100);

                return new ResourceGenerationResponse(
                        runId,
                        artifacts.stream().peek(this::attachSignedMediaUrls).toList(),
                        persistenceManager.findRunSteps(runId),
                        firstNonBlank(reviewSummary, "可审计多智能体编排完成: " + result.metadata().getOrDefault("workflowState", "COMPLETED"))
                );

            } catch (com.visionary.exception.AgentOrchestrationException e) {
                // ========== 熔断触发：优雅降级到 Legacy 模式 ==========
                log.warn("[CircuitBreaker] ReAct orchestration circuit breaker triggered: {}", e.getCircuitSummary());

                // 记录熔断事件
                saveStep(runId, session.getId(), "ReActCircuitBreaker", 88,
                        "Circuit Breaker Triggered", e.getCircuitSummary(),
                        "熔断原因: " + e.getReason().getDescription() +
                        ", 迭代次数: " + e.getIterationsCompleted() +
                        ", 失败工具: " + String.join(", ", e.getFailedToolAttempts()));

                emitProgress(listener, runId, "recovery", "ReActCircuitBreaker", 88,
                        "ReAct 模式触发熔断，正在切换至稳定模型生成链路",
                        e.getCircuitSummary(), 85);

                // 如果存在部分结果，尝试保存
                if (e.hasPartialResult()) {
                    log.info("[CircuitBreaker] Partial results available, preserving {} iterations of work",
                        e.getIterationsCompleted());
                    // 部分结果可以通过 e.getPartialResult() 获取，这里可以扩展保存逻辑
                }

                // 构建降级原因
                fallbackReason = "ReAct circuit breaker triggered: " + e.getReason().getDescription() +
                        " (iterations: " + e.getIterationsCompleted() + ")";

                // 优雅降级到 Legacy 模式
                emitProgress(listener, runId, "recovery", "LegacyPipeline", 90,
                        "编排已自动恢复，正在继续生成资源", fallbackReason, 90);

                // 调用 Legacy 模式生成资源
                return executeLegacyGenerationPath(request, session, runId, topic, blackboard, listener, fallbackReason);

            } catch (Exception ex) {
                log.error("Multi-agent workflow failed: {}", ex.getMessage(), ex);
                fallbackReason = "Supervisor workflow failed: " + ex.getMessage();

                // 对于 ReAct 模式，也降级到 Legacy 而不是直接失败
                if (mode == OrchestrationMode.REACT) {
                    log.warn("[ReActFallback] ReAct mode failed with exception, falling back to legacy mode");
                    emitProgress(listener, runId, "recovery", "LegacyPipeline", 90,
                            "ReAct 执行异常，正在切换至稳定模型生成链路", fallbackReason, 90);
                    saveStep(runId, session.getId(), "LegacyPipeline", 90,
                            "ReAct exception recovery", fallbackReason, "切换至稳定模型生成链路");

                    // 调用 Legacy 模式生成资源
                    return executeLegacyGenerationPath(request, session, runId, topic, blackboard, listener, fallbackReason);
                }

                emitProgress(listener, runId, "degraded", "LegacyPipeline", 90,
                        "多智能体主链路失败，已切换降级生成", fallbackReason, 90);
                saveStep(runId, session.getId(), "LegacyPipeline", 90,
                        "Supervisor fallback", fallbackReason, "降级结果会在资源元数据中显式标记");
            }
        }

        // Legacy fallback路径（Supervisor 不可用或非 ReAct 模式主链路失败时）
        emitProgress(listener, runId, "workflow", "LegacyPipeline", 1, "Using explicit legacy resource pipeline", topic, 10);
        return executeLegacyGenerationPath(request, session, runId, topic, blackboard, listener, fallbackReason);
    }

    /**
     * 根据 Planner 选中的 Agents 动态装配受限 ToolRegistry。
     * 仅包含被选中的 Agents 对应的工具，避免冗余调用。
     */
    private Map<String, com.visionary.agent.core.Tool> buildRestrictedToolRegistry(List<String> selectedAgents) {
        Map<String, com.visionary.agent.core.Tool> tools = new HashMap<>();

        // 基础工具（必须包含）
        com.visionary.agent.tool.RagRetrieveTool ragTool =
                new com.visionary.agent.tool.RagRetrieveTool(ragRetrievalService, objectMapper);
        tools.put("RAGRetrieveTool", ragTool);

        com.visionary.agent.tool.ArtifactPersistTool persistTool =
                new com.visionary.agent.tool.ArtifactPersistTool(artifactRepository, objectMapper);
        tools.put("ArtifactPersistTool", persistTool);
        tools.put("ProfileMergeTool", profileMergeTool);

        // 根据选中的 Agents 装配对应的 Specialist Tools
        // 映射规则：Agent 角色名 -> 对应的工具
        if (selectedAgents == null || selectedAgents.isEmpty()) {
            return tools; // 只返回基础工具
        }

        // 将所有选中 Agents 对应的 Specialist Tools 注册到工具注册表
        for (String agent : selectedAgents) {
            registerSpecialistToolForAgent(tools, agent);
        }

        log.debug("[HybridArchitecture] 受限 ToolRegistry 装配完成: {} 个工具对应 {} 个 Agents",
                tools.size(), selectedAgents.size());

        return tools;
    }

    /**
     * 为特定 Agent 注册对应的 Specialist Tool
     */
    private void registerSpecialistToolForAgent(Map<String, com.visionary.agent.core.Tool> tools, String agentRole) {
        // 使用工具包装器将 Specialist Tool 转换为 Tool 接口
        switch (agentRole) {
            case "DocAgent", "LectureAgent" -> {
                if (docSpecialistTool != null) {
                    tools.put("DocSpecialistTool", new SpecialistToolAdapter("DocSpecialistTool"));
                }
            }
            case "QuizAgent" -> {
                if (quizSpecialistTool != null) {
                    tools.put("QuizSpecialistTool", new SpecialistToolAdapter("QuizSpecialistTool"));
                }
            }
            case "MindMapAgent" -> {
                if (mindMapSpecialistTool != null) {
                    tools.put("MindMapSpecialistTool", new SpecialistToolAdapter("MindMapSpecialistTool"));
                }
            }
            case "ReadingAgent" -> {
                if (readingSpecialistTool != null) {
                    tools.put("ReadingSpecialistTool", new SpecialistToolAdapter("ReadingSpecialistTool"));
                }
            }
            case "CodingAgent" -> {
                if (codingSpecialistTool != null) {
                    tools.put("CodingSpecialistTool", new SpecialistToolAdapter("CodingSpecialistTool"));
                }
            }
            case "PathAgent" -> {
                if (pathSpecialistTool != null) {
                    tools.put("PathSpecialistTool", new SpecialistToolAdapter("PathSpecialistTool"));
                }
            }
            case "VisualizationAgent" -> {
                if (visualizationSpecialistTool != null) {
                    tools.put("VisualizationSpecialistTool", new SpecialistToolAdapter("VisualizationSpecialistTool"));
                }
            }
            case "CriticAgent" -> {
                if (criticReviewTool != null) {
                    tools.put("CriticReviewTool", new SpecialistToolAdapter("CriticReviewTool"));
                }
            }
            default -> log.debug("[HybridArchitecture] 未知 Agent 角色: {}", agentRole);
        }
    }

    /**
     * Specialist Tool 适配器，将 SpecialistTool 实现包装为 Tool 接口。
     */
    private class SpecialistToolAdapter implements com.visionary.agent.core.Tool {
        private final String name;

        public SpecialistToolAdapter(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            com.visionary.agent.tools.SpecialistTool tool = resolveSpecialistTool(name);
            if (tool != null) {
                return "Specialist tool: " + tool.getToolName();
            }
            return "Specialist tool adapter for " + name;
        }

        @Override
        public com.fasterxml.jackson.databind.JsonNode getParametersSchema() {
            com.fasterxml.jackson.databind.node.ObjectNode schema = objectMapper.createObjectNode();
            schema.put("type", "object");
            schema.putObject("properties");
            return schema;
        }

        @Override
        public com.visionary.agent.core.ToolResult execute(
                com.fasterxml.jackson.databind.JsonNode args,
                com.visionary.agent.core.ToolContext context) {
            try {
                com.visionary.agent.tools.SpecialistTool tool = resolveSpecialistTool(name);
                if (tool == null) {
                    log.warn("[SpecialistToolAdapter] No specialist tool found for: {}", name);
                    return errorResult("Tool not found: " + name);
                }

                com.fasterxml.jackson.databind.node.ObjectNode objectArgs = args != null && args.isObject()
                        ? (com.fasterxml.jackson.databind.node.ObjectNode) args
                        : objectMapper.createObjectNode();

                com.visionary.agent.core.SharedBlackboard blackboard = context.blackboard();
                com.visionary.agent.tools.SpecialistTool.ReActContext toolCtx =
                        new com.visionary.agent.tools.SpecialistTool.ReActContext(
                                context.runId(),
                                objectArgs.path("memoryId").asText(""),
                                firstNonBlank(
                                        objectArgs.path("topic").asText(null),
                                        blackboard != null ? blackboard.getCurrentTopic() : null,
                                        ""
                                ),
                                firstNonBlank(
                                        objectArgs.path("learnerProfile").asText(null),
                                        blackboard != null ? blackboard.getLearnerProfileSnapshot() : null,
                                        ""
                                ),
                                blackboard != null && blackboard.get("weakPointsSnapshot") != null
                                        ? blackboard.get("weakPointsSnapshot").toString()
                                        : "",
                                objectArgs.has("learningSessionId") && !objectArgs.get("learningSessionId").isNull()
                                        ? objectArgs.get("learningSessionId").asLong()
                                        : null
                        );

                String output = tool.executeTool(objectArgs, toolCtx);
                boolean success = output != null && !output.contains("\"status\":\"TOOL_FAILED\"");
                Map<String, Object> data = new HashMap<>();
                data.put("output", output);
                return new com.visionary.agent.core.ToolResult(success, output, data);
            } catch (Exception e) {
                log.error("[SpecialistToolAdapter] Failed to execute tool {}: {}", name, e.getMessage(), e);
                return errorResult("Tool execution failed: " + e.getMessage());
            }
        }

        private com.visionary.agent.core.ToolResult errorResult(String message) {
            Map<String, Object> data = new HashMap<>();
            data.put("error", true);
            data.put("message", message);
            return new com.visionary.agent.core.ToolResult(false, message, data);
        }

        private com.visionary.agent.tools.SpecialistTool resolveSpecialistTool(String toolName) {
            return switch (toolName) {
                case "DocSpecialistTool" -> docSpecialistTool;
                case "QuizSpecialistTool" -> quizSpecialistTool;
                case "MindMapSpecialistTool" -> mindMapSpecialistTool;
                case "ReadingSpecialistTool" -> readingSpecialistTool;
                case "CodingSpecialistTool" -> codingSpecialistTool;
                case "PathSpecialistTool" -> pathSpecialistTool;
                case "VisualizationSpecialistTool" -> visualizationSpecialistTool;
                case "CriticReviewTool" -> criticReviewTool;
                default -> null;
            };
        }
    }
    @Transactional
    public TutoringMultimodalResponse generateTutoringMultimodal(TutoringMultimodalRequest request) {
        LearningSession session = persistenceManager.requireSession(request.learningSessionId());

        String question = firstNonBlank(request.question(), "当前辅导问题");
        String topic = firstNonBlank(request.topic(), session.getTopic(), question);
        String dialogue = firstNonBlank(request.dialogueContext(), question);
        String profile = firstNonBlank(request.learnerProfileSnapshot(), "暂无画像");
        String runId = "TUTOR-" + UUID.randomUUID().toString().substring(0, 8);

        List<ArtifactType> requestedModes = request.modes() == null || request.modes().isEmpty()
                ? List.of(ArtifactType.MINDMAP, ArtifactType.VISUALIZATION)
                : request.modes();
        List<ArtifactType> modes = requestedModes.stream()
                .map(type -> type == ArtifactType.VIDEO_SCRIPT ? ArtifactType.VISUALIZATION : type)
                .distinct()
                .toList();

        RagRetrievalResult rag = retrieveRagForTask(
                com.visionary.agent.AgentTaskType.KNOWLEDGE_DIAGNOSIS,
                question
        );

        List<GeneratedArtifact> artifacts = new ArrayList<>();

        if (modes.contains(ArtifactType.MINDMAP)) {
            String mindmap = generateTutoringMindmap(topic, question, dialogue, profile, rag);
            GeneratedArtifact artifact = persistTutoringArtifact(session.getId(), runId, ArtifactType.MINDMAP,
                    topic + " 辅导导图", mindmap, rag);
            artifacts.add(artifact);
        }

        if (modes.contains(ArtifactType.VISUALIZATION)) {
            String animation = generateTutoringAnimation(topic, question, dialogue, profile, rag);
            GeneratedArtifact artifact = persistTutoringArtifact(session.getId(), runId, ArtifactType.VISUALIZATION,
                    topic + " 演示动画与文字注解", animation, rag);
            artifacts.add(artifact);
        }

        session.setCurrentPhase(LearningSession.LearningPhase.RESOURCE_GENERATION);
        persistenceManager.markResourceGenerationPhase(session);

        String message = artifacts.size() == 2
                ? "已根据辅导上下文生成思维导图与本地演示动画"
                : "已生成 " + artifacts.size() + " 个辅导图解资源";

        return new TutoringMultimodalResponse(runId, artifacts.stream().peek(this::attachSignedMediaUrls).toList(), message);
    }

    private String generateTutoringMindmap(String topic, String question, String dialogue, String profile, RagRetrievalResult rag) {
        if (!deepSeekApiClient.isConfigured()) {
            return "# " + topic + " 辅导导图\n\n" + truncate(dialogue, 800);
        }
        try {
            return deepSeekApiClient.chat(
                    "你是 TutoringAgent 的导图专家。基于辅导对话生成 Mermaid mindmap + 节点说明。",
                    """
                            学生问题：%s
                            辅导对话摘要：
                            %s
                            学生画像：%s
                            RAG 证据：
                            %s

                            输出 Markdown，含 ```mermaid mindmap```，聚焦学生困惑点。
                            """.formatted(question, truncate(dialogue, 1500), truncate(profile, 600), rag.toCitationInstructionBlock()),
                    false
            );
        } catch (Exception e) {
            log.warn("Tutoring mindmap generation failed: {}", e.getMessage());
            return "# " + topic + " 辅导导图\n\n" + truncate(dialogue, 800);
        }
    }

    private String generateTutoringAnimation(String topic, String question, String dialogue, String profile, RagRetrievalResult rag) {
        if (!deepSeekApiClient.isConfigured()) {
            return buildLocalAnimationFallback(topic, question);
        }
        try {
            String generated = deepSeekApiClient.chat(
                    "你是 TutoringAgent 的演示动画专家。输出完全自包含的 HTML/CSS/原生 JavaScript 教学动画，不调用任何外部 CDN、图片、API 或云视频服务。",
                    """
                            学生问题：%s
                            最近辅导内容：
                            %s
                            学生画像：%s
                            RAG 证据：
                            %s

                            输出要求：
                            1. 仅输出可直接放入 iframe srcdoc 的完整 HTML，不要 Markdown 代码围栏；
                            2. 包含 3—6 个演示步骤，每一步都有画面变化与同步中文文字注解；
                            3. 提供播放、暂停、上一步、下一步、重新开始按钮，并显示当前进度；
                            4. 使用 CSS transform/transition 或 requestAnimationFrame，不使用视频文件；
                            5. 事实内容依据 RAG 证据，证据不足处标注“示意”；
                            6. 页面必须适配窄卡片，禁止固定超宽布局。
                            """.formatted(question, truncate(dialogue, 1500), truncate(profile, 600), rag.toCitationInstructionBlock()),
                    false
            );
            return stripHtmlCodeFence(generated);
        } catch (Exception e) {
            log.warn("Tutoring animation generation failed: {}", e.getMessage());
            return buildLocalAnimationFallback(topic, question);
        }
    }

    private String buildLocalAnimationFallback(String topic, String question) {
        String safeTopic = escapeHtml(topic);
        String safeQuestion = escapeHtml(truncate(question, 220));
        return """
                <!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <style>
                *{box-sizing:border-box}body{margin:0;padding:18px;background:linear-gradient(145deg,#f0fdfa,#eef2ff);font:14px/1.6 system-ui;color:#0f172a}
                .demo{max-width:880px;margin:auto}.head{display:flex;justify-content:space-between;gap:12px;align-items:start}.tag{color:#0f766e;font-size:11px;font-weight:800;letter-spacing:.1em}h1{margin:4px 0 6px;font-size:clamp(20px,4vw,30px)}
                .stage{position:relative;min-height:250px;margin:18px 0;padding:22px;border-radius:18px;background:white;box-shadow:0 16px 34px #0f172a14;overflow:hidden}.step{display:none;min-height:190px;place-items:center;text-align:center}.step.active{display:grid;animation:enter .38s ease}.node{width:min(100%%,520px);padding:22px;border:2px solid #14b8a6;border-radius:16px;background:#ccfbf1;font-size:18px;font-weight:750}.note{margin-top:14px;color:#475569;font-size:14px;font-weight:450}.controls{display:flex;flex-wrap:wrap;gap:8px}.controls button{border:1px solid #0d9488;border-radius:9px;padding:8px 13px;background:white;color:#0f766e;cursor:pointer}.controls button.primary{background:#0d9488;color:white}.progress{margin-left:auto;color:#64748b}.notice{margin-top:12px;padding:10px 12px;border-left:3px solid #f59e0b;background:#fffbeb;color:#92400e;font-size:12px}@keyframes enter{from{opacity:0;transform:translateX(18px)}to{opacity:1;transform:none}}@media(max-width:520px){body{padding:10px}.head{display:block}.progress{width:100%%}}
                </style></head><body><main class="demo"><header class="head"><div><span class="tag">LOCAL ANIMATED EXPLAINER</span><h1>%s</h1><div>围绕问题：%s</div></div><strong id="counter">1 / 4</strong></header>
                <section class="stage">
                  <article class="step active"><div><div class="node">① 先明确问题与目标</div><p class="note">把问题拆成“已知条件、需要得到的结果、判断标准”。</p></div></article>
                  <article class="step"><div><div class="node">② 找到关键概念</div><p class="note">从教材或知识库证据中确认核心定义，未知内容不直接当成事实。</p></div></article>
                  <article class="step"><div><div class="node">③ 走完一个最小例子</div><p class="note">逐步观察输入、处理和输出，记录哪一步与预期不同。</p></div></article>
                  <article class="step"><div><div class="node">④ 总结并立即自测</div><p class="note">用自己的语言复述，再修改一个条件检查理解是否仍然成立。</p></div></article>
                </section>
                <nav class="controls"><button onclick="move(-1)">上一步</button><button id="play" class="primary" onclick="toggle()">自动播放</button><button onclick="move(1)">下一步</button><button onclick="restart()">重新开始</button><span class="progress">本地运行 · 无云视频任务</span></nav>
                <p class="notice">当前为透明降级示意动画；知识性结论请以教材引用与 AI 老师回答中的来源为准。</p></main>
                <script>let i=0,t=null;const s=[...document.querySelectorAll('.step')],c=document.getElementById('counter'),p=document.getElementById('play');function show(n){i=(n+s.length)%%s.length;s.forEach((x,k)=>x.classList.toggle('active',k===i));c.textContent=(i+1)+' / '+s.length}function move(d){show(i+d)}function toggle(){if(t){clearInterval(t);t=null;p.textContent='自动播放'}else{p.textContent='暂停';t=setInterval(()=>move(1),1800)}}function restart(){if(t){clearInterval(t);t=null;p.textContent='自动播放'}show(0)}</script></body></html>
                """.formatted(safeTopic, safeQuestion);
    }

    private static String stripHtmlCodeFence(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (!value.startsWith("```")) return value;
        int firstLine = value.indexOf('\n');
        int lastFence = value.lastIndexOf("```");
        return firstLine > 0 && lastFence > firstLine
                ? value.substring(firstLine + 1, lastFence).trim()
                : value;
    }

    private static String escapeHtml(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private GeneratedArtifact persistTutoringArtifact(
            Long sessionId,
            String runId,
            ArtifactType type,
            String title,
            String content,
            RagRetrievalResult rag
    ) {
        GeneratedArtifact artifact = new GeneratedArtifact();
        artifact.setLearningSessionId(sessionId);
        artifact.setRunId(runId);
        artifact.setArtifactType(type);
        artifact.setTitle(title);
        artifact.setContentMarkdown(content);
        artifact.setCitationsJson(toJson(rag.citations()));
        CitationValidator.ValidationResult validation = citationValidator.validate(content, rag);
        PublishGate.PublishDecision publishDecision = publishGate.evaluate(content, rag, validation);
        artifact.setValidationStatus(validation.status());
        artifact.setPublishStatus(publishDecision.publishStatus().name());
        artifact.setVerificationAuditJson(publishDecision.auditJson());
        artifact.setReviewNotes("TutoringAgent 多模态辅导生成 | " + validation.message());
        artifact.setProgress(100);
        GeneratedArtifact saved = saveAndIndexArtifact(artifact);
        return saved != null ? saved : artifact;
    }

    // 新增：构建Tool注册表供Agent使用
    private Map<String, com.visionary.agent.core.Tool> buildToolRegistry() {
        Map<String, com.visionary.agent.core.Tool> tools = new HashMap<>();

        // RAG检索工具
        com.visionary.agent.tool.RagRetrieveTool ragTool =
                new com.visionary.agent.tool.RagRetrieveTool(ragRetrievalService, objectMapper);
        tools.put("RAGRetrieveTool", ragTool);

        // 制品持久化工具
        com.visionary.agent.tool.ArtifactPersistTool persistTool =
                new com.visionary.agent.tool.ArtifactPersistTool(artifactRepository, objectMapper);
        tools.put("ArtifactPersistTool", persistTool);
        tools.put("ProfileMergeTool", profileMergeTool);

        return tools;
    }

    // 新增：转换Blackboard类型
    private com.visionary.agent.core.SharedBlackboard convertToSharedBlackboard(
            AgentBlackboard blackboard,
            ResourceGenerationRequest request
    ) {
        com.visionary.agent.core.SharedBlackboard shared = new com.visionary.agent.core.SharedBlackboard();
        shared.setCurrentTopic(extractTopicFromPlan(blackboard.plan()));
        if (request != null && request.learnerProfileSnapshot() != null) {
            shared.updateProfileSnapshot(request.learnerProfileSnapshot());
        }
        if (request != null && request.weakPointsSnapshot() != null) {
            shared.put("weakPointsSnapshot", request.weakPointsSnapshot());
        }
        return shared;
    }

    // ==================== 混合架构（Hybrid Architecture）专用方法 ====================

    /**
     * 执行混合架构的 Planner 阶段。
     * 调用 PlannerAgent 获取基于 LearnerProfile 的动态执行计划。
     *
     * <p>混合架构核心流程：
     * 1. PlannerAgent 分析 LearnerProfile（学习风格：视觉型/文字型/实践型）
     * 2. 输出 JSON 格式的动态任务执行清单（包含所需 Specialist 列表及依赖）
     * 3. MultiAgentResourceService 拦截清单，按需装配受限 ToolRegistry
     * 4. 将受限 ToolRegistry 传递给 ReActSupervisorAdapter 进行自主调度
     * 5. 如果 Planner 失败，自动回退到全量并行生成逻辑
     */
    private com.visionary.agent.PlannerAgent.DynamicExecutionPlan executeHybridPlannerPhase(
            String runId,
            LearningSession session,
            String topic,
            ResourceGenerationRequest request,
            ResourceGenerationProgressListener listener
    ) {
        log.info("[HybridArchitecture] 启动 PlannerAgent 动态规划阶段, runId={}", runId);

        // 检查 PlannerAgent 是否可用
        if (plannerAgent == null) {
            log.warn("[HybridArchitecture] PlannerAgent 未注入，跳过动态规划");
            throw new IllegalStateException("PlannerAgent not available - not injected");
        }

        // 构建 PlannerAgent 任务
        Map<String, Object> plannerInput = new HashMap<>();
        plannerInput.put("learningSessionId", session.getId());
        plannerInput.put("topic", topic);
        plannerInput.put("learnerProfileSnapshot", request.learnerProfileSnapshot());
        plannerInput.put("weakPointsSnapshot", request.weakPointsSnapshot());
        plannerInput.put("emotionSnapshot", request.emotionSnapshot());
        plannerInput.put("userQuestion", request.topic());
        if (hasExplicitResourceTypes(request)) {
            plannerInput.put("resourceTypes", request.resourceTypes());
        }

        com.visionary.agent.core.AgentTask plannerTask = new com.visionary.agent.core.AgentTask(
                runId + "-planner",
                com.visionary.agent.AgentTaskType.RESOURCE_GENERATION.name(),
                plannerInput,
                List.of("PlannerAgent")
        );

        // 构建 Planner 上下文
        Map<String, com.visionary.agent.core.Tool> plannerTools = buildToolRegistry();
        com.visionary.agent.core.SharedBlackboard plannerBlackboard = new com.visionary.agent.core.SharedBlackboard();
        plannerBlackboard.setCurrentTopic(topic);
        plannerBlackboard.updateProfileSnapshot(request.learnerProfileSnapshot());

        com.visionary.agent.core.MessageBus bus = messageBus != null
                ? messageBus
                : new com.visionary.agent.impl.InMemoryMessageBus();

        Map<String, Object> metadata = new HashMap<>();
        if (listener != null) {
            metadata.put("progressListener", listener);
        }

        com.visionary.agent.core.AgentContext plannerCtx = new com.visionary.agent.core.AgentContext(
                plannerBlackboard,
                plannerTools,
                bus,
                runId,
                metadata
        );

        // 执行 PlannerAgent
        com.visionary.agent.core.AgentResult result = plannerAgent.execute(plannerTask, plannerCtx);

        if (!result.success()) {
            throw new RuntimeException("PlannerAgent execution failed: " + result.output());
        }

        // 从结果中提取动态执行计划
        Object planObj = result.metadata().get("dynamicPlan");
        if (planObj instanceof com.visionary.agent.PlannerAgent.DynamicExecutionPlan) {
            return (com.visionary.agent.PlannerAgent.DynamicExecutionPlan) planObj;
        }

        // 尝试从 blackboard 获取
        Object blackboardPlan = plannerBlackboard.get("planner_dynamic_execution_plan");
        if (blackboardPlan instanceof com.visionary.agent.PlannerAgent.DynamicExecutionPlan) {
            return (com.visionary.agent.PlannerAgent.DynamicExecutionPlan) blackboardPlan;
        }

        throw new IllegalStateException("PlannerAgent did not return valid dynamic execution plan");
    }

    // 新增：从plan中提取主题
    private String extractTopicFromPlan(String plan) {
        if (plan == null) return "未命名主题";
        // 提取第一行非空内容作为主题
        return plan.lines()
                .filter(line -> !line.trim().isEmpty())
                .findFirst()
                .orElse("未命名主题")
                .replace("学习目标：", "")
                .trim();
    }

    // 将原有第107-191行代码提取为独立方法
    private ResourceGenerationResponse executeLegacyGenerationPath(
            ResourceGenerationRequest request,
            LearningSession session,
            String runId,
            String topic,
            AgentBlackboard blackboard,
            ResourceGenerationProgressListener listener,
            String fallbackReason
    ) {
        List<GeneratedArtifact> artifacts = new ArrayList<>();
        int order = 2;

        for (ArtifactType type : resolveTypes(request.resourceTypes())) {
            RagRetrievalResult rag = retrieveRagForTask(
                    com.visionary.agent.AgentTaskType.RESOURCE_GENERATION,
                    topic + " " + type.name()
            );
            ArtifactContent generated = generateArtifactContent(type, topic, blackboard.plan(), request, rag);
            String content = generated.content();
            CitationValidator.ValidationResult validation = validateContent(content, rag);
            CritiqueResult critique = critiqueArtifact(type, topic, content, request, validation, rag);
            blackboard.critiques().add(type.name() + ": " + critique.message());
            saveStep(
                    runId,
                    session.getId(),
                    "CriticAgent",
                    order++,
                    type.name() + " 教学完整性/画像适配/引用审查",
                    critique.message(),
                    critique.needsRevision() ? "要求 " + agentName(type) + " 返修一次" : "审查通过"
            );

            GeneratedArtifact artifact = saveArtifact(runId, session.getId(), type, topic, content, rag, validation);
            double previousCompositeScore = compositeScoreCalculator.computeCompositeScore(
                    artifact, extractFactualityScore(critique.message()) * 100.0D);
            GovernanceBestSolutionTracker bestTracker = new GovernanceBestSolutionTracker(
                    compositeScoreCalculator, previousCompositeScore);
            String bestContent = content;
            CitationValidator.ValidationResult bestValidation = validation;
            CritiqueResult bestCritique = critique;

            int revisionRound = 0;
            int maxRevisionRounds = governanceProperties.getMaxRevisionRounds();
            while (critique.needsRevision()
                    && deepSeekApiClient.isConfigured()
                    && revisionRound < maxRevisionRounds) {
                revisionRound++;
                String revisedPlan = replanAfterCritique(topic, type, blackboard, critique, request, rag);
                blackboard = new AgentBlackboard(revisedPlan, blackboard.critiques());
                saveStep(
                        runId,
                        session.getId(),
                        "PlannerAgent(Replan#" + revisionRound + ")",
                        order++,
                        type.name() + " critique handoff",
                        revisedPlan,
                        "CriticAgent handed off concrete defects; PlannerAgent updated the shared blackboard before specialist revision"
                );
                content = repairArtifactContent(type, topic, content, critique.message(), revisedPlan, rag);
                validation = validateContent(content, rag);
                saveStep(
                        runId,
                        session.getId(),
                        agentName(type) + "Revision#" + revisionRound,
                        order++,
                        type.name() + " 返修",
                        summarize(content),
                        validation.message()
                );
                critique = critiqueArtifact(type, topic, content, request, validation, rag);
                blackboard.critiques().add(type.name() + " round " + revisionRound + ": " + critique.message());
                saveStep(
                        runId,
                        session.getId(),
                        "CriticAgent(Review#" + revisionRound + ")",
                        order++,
                        type.name() + " post-revision verification",
                        critique.message(),
                        critique.needsRevision() ? "Still requires revision or human review" : "Revision accepted"
                );
                artifact.setContentMarkdown(content);
                artifact.setValidationStatus(validation.status());
                double llmScore = extractFactualityScore(critique.message()) * 100.0D;
                if (bestTracker.registerIfBetter(artifact, llmScore)) {
                    bestContent = content;
                    bestValidation = validation;
                    bestCritique = critique;
                }
                if (shouldBreakOnGovernance(artifact, content, validation, revisionRound, previousCompositeScore, critique.message())) {
                    content = bestContent;
                    validation = bestValidation;
                    critique = bestCritique;
                    artifact.setContentMarkdown(bestContent);
                    artifact.setValidationStatus(bestValidation.status());
                    break;
                }
                previousCompositeScore = compositeScoreCalculator.computeCompositeScore(artifact, llmScore);
            }

            if (critique.needsRevision()) {
                saveStep(
                        runId,
                        session.getId(),
                        "HumanReviewGate",
                        order++,
                        type.name() + " bounded-loop stop",
                        critique.message(),
                                    "Max orchestrated revision rounds reached; artifact retained with explicit validation status for teacher review"
                );
            }

            artifact.setContentMarkdown(content);
            artifact.setCitationsJson(toJson(rag.citations()));
            artifact.setValidationStatus(validation.status());
            artifact.setReviewNotes(critique.message());
            PublishGate.PublishDecision publishDecision = publishGate.evaluate(content, rag, validation);
            artifact.setPublishStatus(publishDecision.publishStatus().name());
            artifact.setVerificationAuditJson(publishDecision.auditJson());
            if (!generated.modelGenerated()) {
                markArtifactGenerationMode(artifact, "MODEL_FALLBACK", generated.fallbackReason());
            } else {
                generationFallbackService.ensureLiveGenerationMode(
                        artifact,
                        fallbackReason == null || fallbackReason.isBlank()
                                ? "LEGACY_PIPELINE"
                                : "LIVE_RECOVERY",
                        agentName(type));
                if (fallbackReason != null && !fallbackReason.isBlank()) {
                    artifact.setReviewNotes(critique.message()
                            + " | [OrchestrationRecovery] " + truncate(fallbackReason, 240));
                }
            }
            saveAndIndexArtifact(artifact);
            artifacts.add(artifact);
            saveStep(
                    runId,
                    session.getId(),
                    agentName(type),
                    order++,
                    type.name() + " / " + topic,
                    summarize(content),
                    validation.message()
            );
        }

        String review = review(artifacts);
        saveStep(runId, session.getId(), "ReviewAgent", order, "全部资源产物", review, "已完成引用与可用性审查");
        session.setCurrentPhase(LearningSession.LearningPhase.RESOURCE_GENERATION);
        session.setStreamingHandout(artifacts.stream()
                .filter(item -> item.getArtifactType() == ArtifactType.HANDOUT)
                .findFirst()
                .map(GeneratedArtifact::getContentMarkdown)
                .orElse(session.getStreamingHandout()));
        persistenceManager.markResourceGenerationPhase(session);

        emitProgress(listener, runId, "workflow", "ReviewAgent", order, "资源生成完成", review, 100);

        boolean containsDegradedArtifact = artifacts.stream().anyMatch(this::isDegradedArtifact);
        return new ResourceGenerationResponse(
                runId,
                artifacts,
                persistenceManager.findRunSteps(runId),
                containsDegradedArtifact
                        ? "部分资源因主模型不可用而使用透明降级内容: " + review
                        : (fallbackReason == null || fallbackReason.isBlank()
                        ? "实时模型生成完成: " + review
                        : "实时模型生成完成（编排已自动恢复）: " + review)
        );
    }

    private String plan(String topic, ResourceGenerationRequest request, LearningSession session) {
        return """
                学习目标：%s
                学生画像：%s
                薄弱点：%s
                情绪/专注：%s
                计划：先补概念，再练习，再做代码实操，最后生成视频/动画脚本和分镜占位。
                """.formatted(
                topic,
                firstNonBlank(request.learnerProfileSnapshot(), "暂无画像"),
                firstNonBlank(request.weakPointsSnapshot(), "暂无薄弱点记录"),
                firstNonBlank(request.emotionSnapshot(), session.getLastEmotionSnapshot(), "暂无情绪信号")
        ).trim();
    }

    private ArtifactContent generateArtifactContent(
            ArtifactType type,
            String topic,
            String planning,
            ResourceGenerationRequest request,
            RagRetrievalResult rag
        ) {
        if (!deepSeekApiClient.isConfigured()) {
            return new ArtifactContent(
                    fallbackContent(type, topic, planning, rag),
                    false,
                    "主模型 API 未配置"
            );
        }
        try {
            String content = deepSeekApiClient.chat(
                    systemPrompt(type),
                    userPrompt(type, topic, planning, request, rag),
                    false
            );
            if (content == null || content.isBlank()) {
                return new ArtifactContent(
                        fallbackContent(type, topic, planning, rag),
                        false,
                        "主模型返回空内容"
                );
            }
            return new ArtifactContent(content, true, "");
        } catch (Exception e) {
            log.warn("{} failed, using fallback: {}", type, e.getMessage());
            return new ArtifactContent(
                    fallbackContent(type, topic, planning, rag),
                    false,
                    "主模型调用失败: " + firstNonBlank(e.getMessage(), e.getClass().getSimpleName())
            );
        }
    }

    private record ArtifactContent(String content, boolean modelGenerated, String fallbackReason) {
    }

    private String repairArtifactContent(
            ArtifactType type,
            String topic,
            String previousContent,
            String critique,
            String revisedPlan,
            RagRetrievalResult rag
    ) {
        return criticReviewService.repair(type, topic, previousContent, critique, revisedPlan, rag);
    }

    private CritiqueResult critiqueArtifact(
            ArtifactType type,
            String topic,
            String content,
            ResourceGenerationRequest request,
            CitationValidator.ValidationResult validation,
            RagRetrievalResult rag
    ) {
        CriticReviewDecision decision = criticReviewService.critique(
                type, topic, content, request, validation, rag);
        return new CritiqueResult(decision.needsRevision(), decision.message());
    }

    private String replanAfterCritique(
            String topic,
            ArtifactType type,
            AgentBlackboard blackboard,
            CritiqueResult critique,
            ResourceGenerationRequest request,
            RagRetrievalResult rag
    ) {
        return criticReviewService.replan(
                topic,
                type,
                agentName(type),
                blackboard.plan(),
                blackboard.critiques(),
                new CriticReviewDecision(critique.needsRevision(), critique.message()),
                request,
                rag
        );
    }

    private String systemPrompt(ArtifactType type) {
        return """
                你是智眸学伴多智能体资源工厂中的 %s。
                必须输出可直接给学生使用的内容，不要只写计划。
                模型已有知识与用户问题是主要生成依据；知识库只是可选补充，不是生成前提。
                实际采用知识库材料时，只能使用检索上下文中的 citationId；未采用或没有材料时正常完成内容且不输出引用。
                不得仅因知识库未命中就在学习正文中写“证据不足”、缩减内容或改成模板。
                """.formatted(agentName(type));
    }

    private String userPrompt(ArtifactType type, String topic, String planning, ResourceGenerationRequest request, RagRetrievalResult rag) {
        return """
                主题：%s
                资源类型：%s
                编排计划：
                %s

                学生画像：%s
                薄弱点：%s
                情绪/专注：%s

                可选知识库补充材料：
                %s

                输出要求：
                %s
                """.formatted(
                topic,
                type.name(),
                planning,
                firstNonBlank(request.learnerProfileSnapshot(), "暂无"),
                firstNonBlank(request.weakPointsSnapshot(), "暂无"),
                firstNonBlank(request.emotionSnapshot(), "暂无"),
                rag.toCitationInstructionBlock(),
                outputContract(type)
        );
    }

    private String outputContract(ArtifactType type) {
        return switch (type) {
            case HANDOUT -> "生成 Markdown 讲义：学习目标、核心概念、例题、常见误区、3 个练习。";
            case QUIZ -> "生成题库：至少 6 题，含题型、难度、答案和解析。";
            case MINDMAP -> "生成 Mermaid mindmap 或 JSON nodes/edges，并附每个节点的学习说明。";
            case LEARNING_PATH -> "生成分步骤学习路径：顺序、预计时间、完成标准、推荐资源。";
            case CODE_PRACTICE -> "生成可运行代码实操：文件结构、代码、任务、预期输出和调试提示。";
            case EXTENDED_READING -> """
                    生成一章学生可以直接学习的拓展教材正文，而不是资料推荐列表。要求：
                    1. 正文 1200—5000 个中文字符，包含目录、学习目标、先修知识、3—6节核心正文；
                    2. 解释“是什么、为什么、怎么做、何时使用”，并至少给出1个例子、常见误区、本章小结和3个思考题；
                    3. 根据学生画像与薄弱点调整术语密度、推导深度和例子；
                    4. 对有证据支撑的陈述保留 citationId；没有证据的说明明确标为通用教学说明；
                    5. 延伸阅读只列证据中真实存在的材料，不要伪造论文、作者、链接、出处或 citationId。
                    """;
            case VIDEO_SCRIPT -> "历史类型仅兼容读取；新内容必须生成 VISUALIZATION 本地动画与同步文字注解。";
            case VISUALIZATION -> """
                    生成完全自包含的本地教学演示动画（HTML + CSS + 原生 JavaScript 单文件）。要求：
                    1. 不引用 CDN、外部图片、API、视频文件或任何云端生成服务；
                    2. 设计 3—6 个与主题和学生薄弱点直接相关的演示步骤；
                    3. 每一步都有同步中文文字注解，解释画面变化、知识含义和易错点；
                    4. 提供播放、暂停、上一步、下一步、重置和进度提示，适配窄卡片；
                    5. 用 CSS 动画或 requestAnimationFrame 表达过程，必要时允许学生调整参数；
                    6. 代码注释中标注数据来源 citationId，证据不足处明确标为“示意”。
                    """;
        };
    }

    private GeneratedArtifact saveArtifact(
            String runId,
            Long sessionId,
            ArtifactType type,
            String topic,
            String content,
            RagRetrievalResult rag,
            CitationValidator.ValidationResult validation
    ) {
        GeneratedArtifact artifact = new GeneratedArtifact();
        artifact.setLearningSessionId(sessionId);
        artifact.setRunId(runId);
        artifact.setArtifactType(type);
        artifact.setTitle(title(type));

        artifact.setContentMarkdown(content);
        artifact.setContentJson(toJson(Map.of(
                "type", type.name(),
                "agent", agentName(type),
                "summary", summarize(content)
        )));

        artifact.setCitationsJson(toJson(rag.citations()));
        artifact.setValidationStatus(validation.status());
        artifact.setReviewNotes(validation.message());
        artifact.setProgress(100);
        attachGroundingAudit(artifact, artifact.getContentMarkdown(), rag);
        GeneratedArtifact saved = saveAndIndexArtifact(artifact);
        return saved != null ? saved : artifact;
    }

    /**
     * Side-path grounding audit — does not alter RAG retrieval or generation flow.
     */
    private void attachGroundingAudit(GeneratedArtifact artifact, String content, RagRetrievalResult rag) {
        if (groundingEvaluationEngine == null || artifact == null) {
            return;
        }
        List<String> chunks = extractRetrievedChunks(rag);
        GroundingMetrics metrics = groundingEvaluationEngine.evaluate(content, chunks);
        try {
            Map<String, Object> audit = new HashMap<>();
            if (artifact.getVerificationAuditJson() != null && !artifact.getVerificationAuditJson().isBlank()) {
                audit.putAll(objectMapper.readValue(
                        artifact.getVerificationAuditJson(),
                        new TypeReference<Map<String, Object>>() {}));
            }
            audit.put("groundingMetrics", metrics);
            audit.put("highRiskOfHallucination", metrics.isHighRiskOfHallucination());
            artifact.setVerificationAuditJson(objectMapper.writeValueAsString(audit));
        } catch (Exception e) {
            log.warn("Grounding audit persistence skipped: {}", e.getMessage());
        }
    }

    private static List<String> extractRetrievedChunks(RagRetrievalResult rag) {
        if (rag == null || rag.citations() == null || rag.citations().isEmpty()) {
            return List.of();
        }
        return rag.citations().stream()
                .map(RagCitation::excerpt)
                .filter(excerpt -> excerpt != null && !excerpt.isBlank())
                .toList();
    }

    private void markArtifactGenerationMode(GeneratedArtifact artifact, String mode, String reason) {
        generationFallbackService.markGenerationMode(
                artifact,
                mode,
                reason,
                artifact != null && artifact.getArtifactType() != null ? agentName(artifact.getArtifactType()) : "UnknownAgent",
                artifact != null ? summarize(artifact.getContentMarkdown()) : "",
                ""
        );
    }

    private void saveStep(String runId, Long sessionId, String agentName, int order, String input, String output, String critique) {
        persistenceManager.saveStep(runId, sessionId, agentName, order, input, output, critique);
        emitProgress(
                ACTIVE_PROGRESS_LISTENER.get(),
                runId,
                "agent_step",
                agentName,
                order,
                firstNonBlank(output, input),
                critique,
                estimateProgress(order)
        );
    }

    private void emitProgress(
            ResourceGenerationProgressListener listener,
            String runId,
            String phase,
            String agentName,
            int stepOrder,
            String message,
            String detail,
            int progressPercent
    ) {
        if (listener == null) {
            return;
        }
        try {
            listener.onProgress(new ResourceGenerationProgressEvent(
                    runId,
                    phase,
                    agentName,
                    stepOrder,
                    message,
                    detail,
                    progressPercent
            ));
        } catch (Exception e) {
            log.warn("Resource generation progress listener failed: {}", e.getMessage());
        }
    }

    private int estimateProgress(int stepOrder) {
        return Math.min(94, 8 + stepOrder * 6);
    }

    private void attachSignedMediaUrls(GeneratedArtifact artifact) {
        persistenceManager.attachSignedMediaUrls(artifact);
    }

    private String fallbackContent(ArtifactType type, String topic, String planning, RagRetrievalResult rag) {
        return """
                # %s

                > 生成模式：本地降级模板。配置 DeepSeek 后将由对应资源 Agent 生成更完整内容。

                ## 学习主题
                %s

                ## 个性化依据
                %s

                ## 检索依据
                %s

                ## 可执行内容
                %s
                """.formatted(title(type), topic, planning, rag.toCitationInstructionBlock(), fallbackBody(type));
    }

    private String fallbackBody(ArtifactType type) {
        return switch (type) {
            case HANDOUT -> "讲义包含：概念解释、例题、误区提醒和课后练习。";
            case QUIZ -> "题库包含：2 道概念题、2 道推导题、2 道实操题，并给出答案解析。";
            case MINDMAP -> """
                    ```mermaid
                    mindmap
                      root((当前主题))
                        基础概念
                        算法步骤
                        公式依赖
                        代码实操
                    ```
                    """;
            case LEARNING_PATH -> "1. 补基础 2. 看讲义 3. 做题库 4. 写代码 5. 上传作业复盘。";
            case CODE_PRACTICE -> "创建一个最小 Python 示例，打印输入张量形状并检查维度匹配。";
            case EXTENDED_READING -> """
                    # 拓展阅读（透明降级版）

                    > 当前生成模型暂不可用。系统不会用虚构论文或未经验证的知识填充教材，以下内容用于引导你基于可追溯资料继续学习。

                    ## 学习目标
                    1. 找出本主题的核心定义、前置概念和适用边界。
                    2. 从检索证据中区分已验证事实、合理推断与待核对问题。
                    3. 完成一个最小例子，并把无法解释的步骤交给 AI 老师继续追问。

                    ## 学习步骤
                    1. 阅读上方“检索依据”，圈出三个关键词并用自己的语言复述。
                    2. 为每个关键词写出“输入—处理—输出”，形成最小知识链。
                    3. 对照教材核验知识链，把可靠资料提交到教材库候选区，而不是自动抓取后直接入库。
                    4. 完成后回答：哪里最容易混淆？什么条件下结论不成立？还缺少什么证据？
                    """;
            case VIDEO_SCRIPT -> "历史类型已迁移为本地演示动画与同步文字注解。";
            case VISUALIZATION -> "生成完全自包含的本地演示动画：3—6 个步骤、同步文字注解、播放/暂停/逐步/重置控制；不调用外部 CDN、API 或云视频。";
        };
    }

    private String review(List<GeneratedArtifact> artifacts) {
        // === ContentSafetyFilter 双重检查（CriticAgent 之后）===
        int blocked = 0;
        int degraded = 0;
        int passed = 0;
        for (GeneratedArtifact a : artifacts) {
            double fact = extractFactualityScore(a);
            GovernanceQualityGateService.SafetyReviewResult safety =
                    governanceQualityGateService.applyArtifactSafetyMetadata(
                            a,
                            fact,
                            agentName(a.getArtifactType()),
                            summarize(a.getContentMarkdown())
                    );
            if ("BLOCKED".equalsIgnoreCase(a.getPublishStatus())) {
                blocked++;
            } else if (!safety.passed()
                    || "DEGRADED".equalsIgnoreCase(a.getPublishStatus())
                    || isDegradedMetadata(a.getContentJson())) {
                degraded++;
            } else {
                passed++;
            }
            saveAndIndexArtifact(a);
        }

        StringBuilder summary = new StringBuilder("本轮生成 ")
                .append(artifacts.size()).append(" 类资源：")
                .append(passed).append(" 类达到发布门槛，")
                .append(degraded).append(" 类以降级状态保留，")
                .append(blocked).append(" 类已拦截。")
                .append(" 已执行引用、事实性、教学完整性与内容安全校验。");
        if (degraded > 0 || blocked > 0) {
            summary.append(" 未通过项不会标记为已验证，可在修复配置后重试生成。");
        }
        return summary.toString();
    }

    private List<ArtifactType> resolveTypes(List<ArtifactType> requested) {
        if (requested == null || requested.isEmpty()) {
            return DEFAULT_TYPES;
        }
        return requested.stream()
                .map(type -> type == ArtifactType.VIDEO_SCRIPT ? ArtifactType.VISUALIZATION : type)
                .distinct()
                .toList();
    }

    private boolean hasExplicitResourceTypes(ResourceGenerationRequest request) {
        return request.resourceTypes() != null && !request.resourceTypes().isEmpty();
    }

    private List<String> mergeRequiredAgents(List<String> baseAgents, ResourceGenerationRequest request) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (baseAgents != null) {
            merged.addAll(baseAgents);
        }
        merged.add("PlannerAgent");
        if (hasExplicitResourceTypes(request)) {
            for (ArtifactType type : resolveTypes(request.resourceTypes())) {
                merged.add(agentName(type));
            }
        }
        merged.add("CriticAgent");
        return new ArrayList<>(merged);
    }

    private String title(ArtifactType type) {
        return switch (type) {
            case HANDOUT -> "专业课程讲义";
            case QUIZ -> "分层练习题库";
            case MINDMAP -> "知识点思维导图";
            case LEARNING_PATH -> "个性化学习路径";
            case CODE_PRACTICE -> "代码实操案例";
            case EXTENDED_READING -> "拓展阅读材料";
            case VIDEO_SCRIPT -> "历史动画内容（已迁移）";
            case VISUALIZATION -> "本地演示动画与文字注解";
        };
    }

    static String agentName(ArtifactType type) {
        return switch (type) {
            case HANDOUT -> "DocAgent";
            case QUIZ -> "QuizAgent";
            case MINDMAP -> "MindMapAgent";
            case LEARNING_PATH -> "PathAgent";
            case CODE_PRACTICE -> "CodingAgent";
            case EXTENDED_READING -> "ReadingAgent";
            case VIDEO_SCRIPT -> "VisualizationAgent";
            case VISUALIZATION -> "VisualizationAgent";
        };
    }

    private String summarize(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        return truncate(content.replaceAll("\\s+", " ").trim(), 240);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "...";
    }

    /**
     * Post-process artifacts persisted by Supervisor specialists:
     * Citation + Factuality + Critic repair loop, video tasks, and safety metadata.
     */
    private String finalizeSupervisorArtifacts(
            String runId,
            Long sessionId,
            String topic,
            ResourceGenerationRequest request,
            AgentBlackboard blackboard,
            boolean enforceCoverage
    ) {
        List<GeneratedArtifact> batch = persistenceManager.findRunArtifacts(runId);
        if (batch.isEmpty()) {
            batch = persistenceManager.findSessionArtifacts(sessionId).stream()
                    .filter(a -> runId.equals(a.getRunId()))
                    .toList();
        }
        if (enforceCoverage) {
            ensureSupervisorArtifactCoverage(runId, sessionId, topic, request, blackboard, batch);
            batch = persistenceManager.findRunArtifacts(runId);
        }

        int order = 2;
        for (GeneratedArtifact artifact : batch) {
            ArtifactType type = artifact.getArtifactType();
            RagRetrievalResult rag = retrieveRagForTask(
                    com.visionary.agent.AgentTaskType.RESOURCE_GENERATION,
                    topic + " " + type.name()
            );

            String content = artifact.getContentMarkdown() != null ? artifact.getContentMarkdown() : "";
            CitationValidator.ValidationResult validation = validateContent(content, rag);
            PublishGate.PublishDecision publishDecision = publishGate.evaluate(content, rag, validation);
            CritiqueResult critique = critiqueArtifact(type, topic, content, request, validation, rag);
            blackboard.critiques().add(type.name() + ": " + critique.message());

            saveStep(
                    runId,
                    sessionId,
                    "CriticAgent",
                    order++,
                    type.name() + " 教学完整性/画像适配/引用审查",
                    critique.message(),
                    critique.needsRevision() ? "要求 " + agentName(type) + " 返修一次" : "审查通过"
            );

            int revisionRound = 0;
            AgentBlackboard workingBoard = blackboard;
            double previousCompositeScore = compositeScoreCalculator.computeCompositeScore(
                    artifact, extractFactualityScore(critique.message()) * 100.0D);
            GovernanceBestSolutionTracker bestTracker = new GovernanceBestSolutionTracker(
                    compositeScoreCalculator, previousCompositeScore);
            String bestContent = content;
            CitationValidator.ValidationResult bestValidation = validation;
            CritiqueResult bestCritique = critique;
            int maxRevisionRounds = governanceProperties.getMaxRevisionRounds();
            while (critique.needsRevision()
                    && deepSeekApiClient.isConfigured()
                    && revisionRound < maxRevisionRounds) {
                revisionRound++;
                String revisedPlan = replanAfterCritique(topic, type, workingBoard, critique, request, rag);
                workingBoard = new AgentBlackboard(revisedPlan, workingBoard.critiques());
                saveStep(
                        runId,
                        sessionId,
                        "PlannerAgent(Replan#" + revisionRound + ")",
                        order++,
                        type.name() + " critique handoff",
                        revisedPlan,
                        "CriticAgent 将缺陷交给 PlannerAgent 重规划后返修"
                );

                content = repairArtifactContent(type, topic, content, critique.message(), revisedPlan, rag);
                validation = validateContent(content, rag);
                saveStep(
                        runId,
                        sessionId,
                        agentName(type) + "Revision#" + revisionRound,
                        order++,
                        type.name() + " 返修",
                        summarize(content),
                        validation.message()
                );

                critique = critiqueArtifact(type, topic, content, request, validation, rag);
                workingBoard.critiques().add(type.name() + " round " + revisionRound + ": " + critique.message());
                saveStep(
                        runId,
                        sessionId,
                        "CriticAgent(Review#" + revisionRound + ")",
                        order++,
                        type.name() + " post-revision verification",
                        critique.message(),
                        critique.needsRevision() ? "仍需人工复核" : "返修通过"
                );
                artifact.setContentMarkdown(content);
                artifact.setValidationStatus(validation.status());
                double llmScore = extractFactualityScore(critique.message()) * 100.0D;
                if (bestTracker.registerIfBetter(artifact, llmScore)) {
                    bestContent = content;
                    bestValidation = validation;
                    bestCritique = critique;
                }
                if (shouldBreakOnGovernance(artifact, content, validation, revisionRound, previousCompositeScore, critique.message())) {
                    content = bestContent;
                    validation = bestValidation;
                    critique = bestCritique;
                    artifact.setContentMarkdown(bestContent);
                    artifact.setValidationStatus(bestValidation.status());
                    break;
                }
                previousCompositeScore = compositeScoreCalculator.computeCompositeScore(artifact, llmScore);
            }

            artifact.setContentMarkdown(content);
            artifact.setCitationsJson(toJson(rag.citations()));
            artifact.setValidationStatus(validation.status());
            publishDecision = publishGate.evaluate(content, rag, validation);
            artifact.setPublishStatus(publishDecision.publishStatus().name());
            artifact.setVerificationAuditJson(publishDecision.auditJson());
            artifact.setReviewNotes(critique.message());

            applyArtifactSafetyMetadata(artifact, extractFactualityScore(critique.message()));
            generationFallbackService.ensureLiveGenerationMode(
                    artifact, "REACT_MULTI_AGENT", agentName(type));
            saveAndIndexArtifact(artifact);

            saveStep(
                    runId,
                    sessionId,
                    agentName(type),
                    order++,
                    type.name() + " / " + topic,
                    summarize(content),
                    validation.message()
            );
        }

        List<GeneratedArtifact> refreshed = persistenceManager.findRunArtifacts(runId);
        String review = review(refreshed.isEmpty() ? batch : refreshed);
        saveStep(runId, sessionId, "ReviewAgent", order, "全部资源产物", review, "已完成引用与可用性审查");
        return review;
    }

    private void ensureSupervisorArtifactCoverage(
            String runId,
            Long sessionId,
            String topic,
            ResourceGenerationRequest request,
            AgentBlackboard blackboard,
            List<GeneratedArtifact> currentBatch
    ) {
        List<ArtifactType> expectedTypes = resolveTypes(request.resourceTypes());
        List<ArtifactType> presentTypes = currentBatch.stream()
                .map(GeneratedArtifact::getArtifactType)
                .toList();
        for (ArtifactType type : expectedTypes) {
            if (presentTypes.contains(type)) {
                continue;
            }
            RagRetrievalResult rag = retrieveRagForTask(
                    com.visionary.agent.AgentTaskType.RESOURCE_GENERATION,
                    topic + " " + type.name()
            );
            String reason = agentName(type)
                    + " did not persist an artifact before review; the stable generation path filled the requested type.";
            ArtifactContent generated = generateArtifactContent(type, topic, blackboard.plan(), request, rag);
            String content = generated.content();
            CitationValidator.ValidationResult validation = validateContent(content, rag);
            GeneratedArtifact artifact = saveArtifact(runId, sessionId, type, topic, content, rag, validation);
            PublishGate.PublishDecision publishDecision = publishGate.evaluate(content, rag, validation);
            artifact.setPublishStatus(publishDecision.publishStatus().name());
            artifact.setVerificationAuditJson(publishDecision.auditJson());
            if (generated.modelGenerated()) {
                generationFallbackService.ensureLiveGenerationMode(
                        artifact, "SPECIALIST_LIVE_RECOVERY", agentName(type));
                artifact.setReviewNotes(validation.message() + " | [SpecialistRecovery] " + reason);
            } else {
                markArtifactGenerationMode(artifact, "SPECIALIST_DEGRADED", generated.fallbackReason());
            }
            saveAndIndexArtifact(artifact);
            saveStep(runId, sessionId, agentName(type), 88,
                    type.name() + (generated.modelGenerated() ? " live recovery" : " degraded fallback"),
                    summarize(content), reason);
        }
    }

    private static final Pattern FACTUALITY_SCORE_PATTERN = Pattern.compile(
            "(?i)factuality(?:_|\\s*)score[\\\"']?\\s*[:=]\\s*([0-9.]+)"
    );

    private double extractFactualityScore(GeneratedArtifact artifact) {
        if (artifact == null) {
            return 0.0;
        }
        try {
            JsonNode metadata = objectMapper.readTree(artifact.getContentJson());
            if (metadata != null && metadata.isObject()) {
                for (String field : List.of("factuality_score", "factualityScore")) {
                    if (metadata.path(field).isNumber()) {
                        return clampFactuality(metadata.path(field).asDouble());
                    }
                    if (metadata.path(field).isTextual()) {
                        return clampFactuality(Double.parseDouble(metadata.path(field).asText()));
                    }
                }
                JsonNode nested = metadata.path("safety_flags").path("factuality");
                if (nested.isNumber() || nested.isTextual()) {
                    return clampFactuality(Double.parseDouble(nested.asText()));
                }
            }
        } catch (Exception e) {
            log.debug("No structured factuality score found for artifact {}: {}", artifact.getId(), e.getMessage());
        }
        return extractFactualityScore(artifact.getReviewNotes());
    }

    private double extractFactualityScore(String critiqueMessage) {
        if (critiqueMessage == null) {
            return 0.5;
        }
        try {
            Matcher matcher = FACTUALITY_SCORE_PATTERN.matcher(critiqueMessage);
            if (matcher.find()) {
                return clampFactuality(Double.parseDouble(matcher.group(1)));
            }
        } catch (NumberFormatException e) {
            log.warn("Failed to parse factualityScore from critique message: {}", e.getMessage());
        }
        return 0.5;
    }

    private static double clampFactuality(double score) {
        return Math.max(0.0, Math.min(1.0, score));
    }

    private static boolean isDegradedMetadata(String contentJson) {
        return contentJson != null && contentJson.contains("\"degraded\":true");
    }

    private boolean shouldBreakOnGovernance(
            GeneratedArtifact artifact,
            String content,
            CitationValidator.ValidationResult validation,
            int revisionRound,
            double previousCompositeScore,
            String criticFeedback
    ) {
        try {
            artifact.setContentMarkdown(content);
            artifact.setValidationStatus(validation.status());
            saveAndIndexArtifact(artifact);

            double llmScore = extractFactualityScore(criticFeedback) * 100.0D;
            BreakerDecision decision = governanceTraceService.recordAndEvaluate(
                    String.valueOf(artifact.getId()),
                    revisionRound,
                    llmScore,
                    previousCompositeScore,
                    criticFeedback
            );
            if (!decision.shouldContinue()) {
                log.warn(
                        "[GovernanceBreaker] artifactId={}, round={}, decisionCode={}, reason={}",
                        artifact.getId(),
                        revisionRound,
                        decision.decisionCode(),
                        decision.reason()
                );
                return true;
            }
        } catch (Exception ex) {
            log.warn(
                    "[GovernanceBreaker] evaluation skipped, legacy bounded loop continues: artifactId={}, round={}, error={}",
                    artifact.getId(),
                    revisionRound,
                    ex.getMessage()
            );
        }
        return false;
    }

    private void applyArtifactSafetyMetadata(GeneratedArtifact artifact) {
        applyArtifactSafetyMetadata(artifact, 0.80);
    }

    private void applyArtifactSafetyMetadata(GeneratedArtifact artifact, double factuality) {
        if (artifact == null) {
            return;
        }
        governanceQualityGateService.applyArtifactSafetyMetadata(
                artifact,
                factuality,
                artifact.getArtifactType() != null ? agentName(artifact.getArtifactType()) : "UnknownAgent",
                summarize(artifact.getContentMarkdown())
        );
    }

    private record CritiqueResult(boolean needsRevision, String message) {
    }

    private record AgentBlackboard(String plan, List<String> critiques) {
    }
}
