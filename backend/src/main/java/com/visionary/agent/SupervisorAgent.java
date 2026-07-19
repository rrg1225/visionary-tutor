package com.visionary.agent;

import com.visionary.agent.core.*;
import com.visionary.dto.ResourceGenerationProgressEvent;
import com.visionary.agent.worker.DistributedHandoffExecutor;
import com.visionary.config.AgentOrchestrationProperties;
import com.visionary.config.GovernanceProperties;
import com.visionary.entity.AgentExecutionLog;
import com.visionary.entity.GeneratedArtifact;
import com.visionary.governance.CompositeScoreCalculator;
import com.visionary.governance.GovernanceBestSolutionTracker;
import com.visionary.governance.GovernanceCircuitBreaker.BreakerDecision;
import com.visionary.rag.RagRetrievalResult;
import com.visionary.rag.RagRetrievalService;
import com.visionary.repository.AgentExecutionLogRepository;
import com.visionary.repository.GeneratedArtifactRepository;
import com.visionary.service.GovernanceTraceService;
import com.visionary.service.ResourceGenerationProgressListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.visionary.agent.AgentCollaborationSupport.*;

/**
 * SupervisorAgent - the central orchestrator.
 * Responsibilities:
 * 1. Decompose incoming task
 * 2. Schedule Specialist Agents in parallel via MessageBus handoff
 * 3. Enforce Critic review per specialist output + bounded revision rounds
 * 4. Update SharedBlackboard
 * 5. Return final result with full trace
 */
@Slf4j
@Service
public class SupervisorAgent implements Agent {

    private final MessageBus messageBus;
    private final Map<String, Agent> agentRegistry;
    private final RagRetrievalService ragRetrievalService;
    private final DistributedHandoffExecutor distributedHandoffExecutor;
    private final AgentOrchestrationProperties orchestrationProps;
    private final GovernanceProperties governanceProperties;
    private final AgentExecutionLogRepository executionLogRepository;
    private final GovernanceTraceService governanceTraceService;
    private final GeneratedArtifactRepository artifactRepository;
    private final CompositeScoreCalculator compositeScoreCalculator;
    private final Executor specialistExecutor;

    public SupervisorAgent(
            MessageBus messageBus,
            @Lazy Map<String, Agent> agentRegistry,
            RagRetrievalService ragRetrievalService,
            @Lazy DistributedHandoffExecutor distributedHandoffExecutor,
            AgentOrchestrationProperties orchestrationProps,
            GovernanceProperties governanceProperties,
            AgentExecutionLogRepository executionLogRepository,
            GovernanceTraceService governanceTraceService,
            GeneratedArtifactRepository artifactRepository,
            CompositeScoreCalculator compositeScoreCalculator,
            @Qualifier("agentSpecialistExecutor") Executor specialistExecutor
    ) {
        this.messageBus = messageBus;
        this.agentRegistry = agentRegistry;
        this.ragRetrievalService = ragRetrievalService;
        this.distributedHandoffExecutor = distributedHandoffExecutor;
        this.orchestrationProps = orchestrationProps;
        this.governanceProperties = governanceProperties;
        this.executionLogRepository = executionLogRepository;
        this.governanceTraceService = governanceTraceService;
        this.artifactRepository = artifactRepository;
        this.compositeScoreCalculator = compositeScoreCalculator;
        this.specialistExecutor = specialistExecutor;
    }

    @Override
    public String getRole() {
        return "Supervisor";
    }

    @Override
    public Set<String> getSupportedTools() {
        return Set.of();
    }

    public enum WorkflowState {
        INIT, PLANNER_DONE, SPECIALISTS_DONE, CRITIC_DONE, REVIEW_DONE, COMPLETED, MANUAL_REVIEW_REQUIRED
    }

    @Override
    public AgentResult execute(AgentTask task, AgentContext context) {
        return runFullWorkflow(task, context);
    }

    public AgentResult runFullWorkflow(AgentTask task, AgentContext context) {
        String runId = context.runId() != null ? context.runId() : UUID.randomUUID().toString();
        SharedBlackboard blackboard = context.blackboard();
        WorkflowState state = WorkflowState.INIT;

        log.info("[Supervisor] Starting bounded multi-agent orchestration {} for task {}", runId, task.type());

        try {
            log.info("[Supervisor] Phase 1: PlannerAgent creating execution plan");
            AgentResult plannerResult = handoffAndWait("PlannerAgent", task, context, runId);
            if (!plannerResult.success()) {
                throw new RuntimeException("PlannerAgent failed: " + plannerResult.output());
            }
            state = WorkflowState.PLANNER_DONE;
            blackboard.addTrace(new SharedBlackboard.AgentRunTrace("Supervisor", "state", state.name(), java.time.Instant.now()));

            List<String> parallelSpecialists = selectParallelSpecialists(task);
            boolean pathRequested = task.requiredRoles() == null
                    || task.requiredRoles().isEmpty()
                    || task.requiredRoles().contains("PathAgent");

            List<AgentResult> specialistResults = new ArrayList<>();
            boolean negotiationEnabled = orchestrationProps.isEnableAgentNegotiation();

            if (negotiationEnabled) {
                log.info("[Supervisor] Phase 2a-1: OUTLINE 协商 — {} specialists", parallelSpecialists.size());
                emitProgress(context, runId, "workflow", "Supervisor", 2, "Agent 协商 · OUTLINE 提案",
                        parallelSpecialists.size() + " 个 Specialist 交换协作意图", 18);
                dispatchSpecialistsInParallel(
                        parallelSpecialists,
                        task,
                        context,
                        runId,
                        blackboard,
                        Map.of(AgentNegotiationProtocol.NEGOTIATION_PHASE_KEY, AgentNegotiationProtocol.PHASE_OUTLINE)
                );
                publishPeerOutlines(blackboard, parallelSpecialists);
                emitProgress(context, runId, "workflow", "Supervisor", 2, "协商黑板已同步 OUTLINE",
                        "已发布 " + parallelSpecialists.size() + " 份协作者提案", 24);
            }

            log.info("[Supervisor] Phase 2a-{}: Dispatching {} specialist agents (FINAL generation)",
                    negotiationEnabled ? "2" : "1", parallelSpecialists.size());
            emitProgress(context, runId, "workflow", "Supervisor", 3, "并行生成 FINAL 资源",
                    parallelSpecialists.size() + " 个 Agent", negotiationEnabled ? 28 : 20);
            Map<String, Object> finalPhaseInput = negotiationEnabled
                    ? Map.of(AgentNegotiationProtocol.NEGOTIATION_PHASE_KEY, AgentNegotiationProtocol.PHASE_FINAL)
                    : Map.of();
            specialistResults.addAll(dispatchSpecialistsInParallel(
                    parallelSpecialists, task, context, runId, blackboard, finalPhaseInput));

            publishPeerSummaries(blackboard, parallelSpecialists);
            if (pathRequested) {
                emitProgress(context, runId, "workflow", "Supervisor", 4, "协作黑板已同步 FINAL 摘要",
                        "PathAgent 读取 " + parallelSpecialists.size() + " 个 Specialist 摘要", 48);

                log.info("[Supervisor] Phase 2b: PathAgent consumes peer summaries from SharedBlackboard");
                AgentResult pathResult = handoffAndWait("PathAgent", task, context, runId);
                if (pathResult.success()) {
                    blackboard.put("PathAgent_result", pathResult);
                }
                specialistResults.add(pathResult);
                emitProgress(context, runId, "workflow", "Supervisor", 5, "PathAgent 已基于协作者摘要生成路径",
                        "消费来源: " + String.join(", ", consumedPeerRoles(blackboard, "PathAgent")), 52);
            }

            state = WorkflowState.SPECIALISTS_DONE;
            blackboard.addTrace(new SharedBlackboard.AgentRunTrace("Supervisor", "state", state.name(), java.time.Instant.now()));

            List<String> allSpecialists = new ArrayList<>(parallelSpecialists);
            if (pathRequested) {
                allSpecialists.add("PathAgent");
            }

            log.info("[Supervisor] Phase 3: CriticAgent reviewing each specialist output");
            int totalRevisionRounds = reviewSpecialistsWithCritic(allSpecialists, task, context, runId, blackboard);

            // 检查是否有任务需要人工审核（Fallback 机制）
            List<String> manualReviewRoles = blackboard.get("manual_review_required_roles") instanceof List<?> list
                    ? list.stream()
                            .filter(item -> item instanceof String)
                            .map(item -> (String) item)
                            .toList()
                    : List.of();

            if (!manualReviewRoles.isEmpty()) {
                state = WorkflowState.MANUAL_REVIEW_REQUIRED;
                blackboard.addTrace(new SharedBlackboard.AgentRunTrace("Supervisor", "state",
                        state.name() + ": " + String.join(", ", manualReviewRoles), java.time.Instant.now()));

                log.warn("[Supervisor] Workflow {} requires manual review for {} specialist(s): {}",
                        runId, manualReviewRoles.size(), String.join(", ", manualReviewRoles));

                emitProgress(context, runId, "workflow", "Supervisor", 5,
                        "Critic 审查完成 - 需要人工审核",
                        manualReviewRoles.size() + " 个 Agent 需要人工干预", 62);

                // 返回包含人工审核状态的最终结果，不继续强制交付
                return buildManualReviewResult(runId, state, manualReviewRoles, allSpecialists.size(),
                        totalRevisionRounds, blackboard);
            }

            emitProgress(context, runId, "workflow", "CriticAgent", 5, "Critic 审查完成",
                    "返修轮次: " + totalRevisionRounds, 62);

            state = WorkflowState.CRITIC_DONE;
            blackboard.addTrace(new SharedBlackboard.AgentRunTrace("Supervisor", "state", state.name(), java.time.Instant.now()));

            log.info("[Supervisor] Phase 4: ReviewAgent final quality gate");
            Map<String, Object> reviewInput = new HashMap<>(task.input());
            reviewInput.put("specialistResults", specialistResults);
            reviewInput.put("criticReports", collectCriticReports(allSpecialists, blackboard));
            AgentResult reviewResult = handoffAndWait("ReviewAgent", task, reviewInput, context, runId);
            blackboard.put("ReviewAgent_report", reviewResult.metadata());
            state = WorkflowState.REVIEW_DONE;

            state = WorkflowState.COMPLETED;
            blackboard.addTrace(new SharedBlackboard.AgentRunTrace("Supervisor", "state", state.name(), java.time.Instant.now()));

            Map<String, Object> finalMeta = new HashMap<>();
            finalMeta.put("runId", runId);
            finalMeta.put("workflowState", state.name());
            finalMeta.put("revisionRounds", totalRevisionRounds);
            finalMeta.put("specialistsExecuted", allSpecialists.size());
            finalMeta.put("specialistSuccessCount",
                    specialistResults.stream().filter(AgentResult::success).count());
            finalMeta.put("parallelDispatch", true);
            finalMeta.put("agentNegotiationEnabled", orchestrationProps.isEnableAgentNegotiation());
            finalMeta.put("negotiationDebateRounds", blackboard.getDebateRound());
            finalMeta.put("debateLog", blackboard.getDebateLog());
            finalMeta.put("distributedOrchestration", distributedHandoffExecutor.isActive());
            finalMeta.put("collaborationModel", orchestrationProps.isEnableAgentNegotiation()
                    ? "agent-to-agent negotiation: OUTLINE proposals + SharedBlackboard alignment + Critic critique protocol"
                    : (distributedHandoffExecutor.isActive()
                    ? "Redis Stream handoff + AgentWorker autonomous execution"
                    : "parallel specialists + PathAgent consumes SharedBlackboard peer summaries"));
            finalMeta.put("peerSummaryRoles", consumedPeerRoles(blackboard, "PathAgent"));
            finalMeta.put("messageBusRole", distributedHandoffExecutor.isActive()
                    ? "redis-stream dispatch with worker execution"
                    : "audit handoff trace + in-process execution");
            finalMeta.put("blackboardKeys", blackboard.keySet());
            finalMeta.put("reviewReport", blackboard.get("ReviewAgent_report"));

            log.info("[Supervisor] Workflow {} completed successfully", runId);
            return new AgentResult(true, "Bounded multi-agent orchestration completed", List.of(), finalMeta, List.of());

        } catch (Exception e) {
            log.error("[Supervisor] Workflow {} failed: {}", runId, e.getMessage(), e);
            throw new RuntimeException("Multi-agent workflow failed: " + e.getMessage(), e);
        }
    }

    static List<String> selectParallelSpecialists(AgentTask task) {
        List<String> defaults = List.of(
                "DocAgent", "QuizAgent", "MindMapAgent", "ReadingAgent", "CodingAgent", "VisualizationAgent"
        );
        if (task == null || task.requiredRoles() == null || task.requiredRoles().isEmpty()) {
            return defaults;
        }
        return defaults.stream().filter(task.requiredRoles()::contains).toList();
    }

    /**
     * 并行调度Specialist Agents执行（不直接操作数据库）。
     * 关键修复：所有数据库写操作延迟到主线程中执行，确保事务一致性。
     */
    private List<AgentResult> dispatchSpecialistsInParallel(
            List<String> specialists,
            AgentTask task,
            AgentContext context,
            String runId,
            SharedBlackboard blackboard
    ) {
        return dispatchSpecialistsInParallel(specialists, task, context, runId, blackboard, Map.of());
    }

    private List<AgentResult> dispatchSpecialistsInParallel(
            List<String> specialists,
            AgentTask task,
            AgentContext context,
            String runId,
            SharedBlackboard blackboard,
            Map<String, Object> inputOverrides
    ) {
        List<CompletableFuture<AgentResult>> futures = specialists.stream()
                    .map(role -> CompletableFuture.supplyAsync(() -> {
                        log.info("[Supervisor] Dispatching {}", role);
                        // 注意：Agent执行内部不应直接操作数据库
                        // 所有持久化操作应通过blackboard或返回值传递到主线程
                        AgentResult result = handoffAndWait(role, task, mergeTaskInput(task.input(), inputOverrides), context, runId);
                        if (result.success()) {
                            synchronized (blackboard) {
                                if (AgentNegotiationProtocol.isOutlinePhase(mergeTaskInput(task.input(), inputOverrides))) {
                                    blackboard.put(role + "_outline_result", result);
                                } else {
                                    blackboard.put(role + "_result", result);
                                }
                            }
                            log.info("[Supervisor] {} completed successfully", role);
                        } else {
                            log.warn("[Supervisor] {} completed with issues: {}", role, result.output());
                        }
                        return result;
                    }, specialistExecutor)
                            .orTimeout(orchestrationProps.getSpecialistTimeoutSeconds(), TimeUnit.SECONDS)
                            .exceptionally(ex -> {
                                String reason = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                                log.warn("[Supervisor] {} isolated after failure/timeout: {}", role, reason);
                                synchronized (blackboard) {
                                    blackboard.put(role + "_degraded", true);
                                    blackboard.put(role + "_failure_reason", reason);
                                }
                                return new AgentResult(
                                        false,
                                        role + " failed or timed out: " + reason,
                                        List.of(),
                                        Map.of(
                                                "artifactType", artifactTypeForRole(role),
                                                "degraded", true,
                                                "failureReason", reason
                                        ),
                                        List.of()
                                );
                            }))
                    .toList();

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .orTimeout(orchestrationProps.getTotalTimeoutSeconds(), TimeUnit.SECONDS)
                .exceptionally(failure -> null)
                .join();
        List<AgentResult> results = futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());

        // All persistence remains on the caller thread where the transaction is owned.
        return results;
    }

    /**
     * Per-specialist Critic review. Re-executes specialists when Critic requests revision.
     * Artifact persistence de-duplication is handled in MultiAgentResourceService.finalizeSupervisorArtifacts.
     * <p>
     * Fallback机制：当重试达到上限仍无法通过审查时，将该任务标记为 MANUAL_REVIEW_REQUIRED
     * 并记录到 AgentExecutionLog，不再强制交付质量不合格的产物。
     */
    private int reviewSpecialistsWithCritic(
            List<String> specialists,
            AgentTask task,
            AgentContext context,
            String runId,
            SharedBlackboard blackboard
    ) {
        int totalRevisions = 0;
        List<String> manualReviewRoles = new ArrayList<>();

        for (String role : specialists) {
            Object stored = blackboard.get(role + "_result");
            if (!(stored instanceof AgentResult specialistResult) || !specialistResult.success()) {
                continue;
            }

            Map<String, Object> criticInput = criticInputFor(task, blackboard, specialistResult, role);
            AgentResult criticResult = handoffAndWait("CriticAgent", task, criticInput, context, runId);
            blackboard.put(role + "_critic", criticResult);

            int revision = 0;
            int maxRevisionRounds = governanceProperties.getMaxRevisionRounds();
            boolean needsRevision = needsRevision(criticResult);
            double previousCompositeScore = resolveCompositeScore(runId, role, criticResult);
            GovernanceBestSolutionTracker bestTracker = new GovernanceBestSolutionTracker(
                    compositeScoreCalculator, previousCompositeScore);
            AgentResult bestSpecialistResult = specialistResult;
            AgentResult bestCriticResult = criticResult;

            while (needsRevision && revision < maxRevisionRounds) {
                revision++;
                totalRevisions++;
                log.info("[Supervisor] Critic requested revision {}/{} for {}", revision, maxRevisionRounds, role);

                AgentResult revised = handoffAndWait(
                        role,
                        task,
                        revisionInputFor(task, specialistResult, criticResult, role, revision),
                        context,
                        runId
                );
                blackboard.put(role + "_result", revised);
                blackboard.put(role + "_revision_round", revision);

                criticInput = criticInputFor(task, blackboard, revised, role);
                criticResult = handoffAndWait("CriticAgent", task, criticInput, context, runId);
                blackboard.put(role + "_critic", criticResult);
                needsRevision = needsRevision(criticResult);

                if (registerBestRevisionSnapshot(runId, role, bestTracker, revised, criticResult)) {
                    bestSpecialistResult = revised;
                    bestCriticResult = criticResult;
                }
                if (shouldBreakOnGovernance(runId, role, revision, previousCompositeScore, criticResult, blackboard)) {
                    blackboard.put(role + "_result", bestSpecialistResult);
                    blackboard.put(role + "_critic", bestCriticResult);
                    needsRevision = needsRevision(bestCriticResult);
                    break;
                }
                previousCompositeScore = resolveCompositeScore(runId, role, criticResult);
            }

            // Fallback 机制：达到最大重试次数后仍需要返修
            if (needsRevision && revision >= maxRevisionRounds) {
                log.warn("[Supervisor] Fallback triggered for {} after {} revision rounds. " +
                        "Quality gate failed, marking for manual review.", role, maxRevisionRounds);

                if (orchestrationProps.isEnableManualReviewFallback()) {
                    // 记录到黑板，供后续流程感知
                    blackboard.put(role + "_manual_review_required", true);
                    blackboard.put(role + "_manual_review_reason", buildFallbackReason(criticResult, role));

                    // 记录需要人工审核的角色
                    manualReviewRoles.add(role);

                    // 记录到 AgentExecutionLog
                    logManualReviewRequired(runId, task, role, criticResult, specialistResult, revision);

                    emitProgress(context, runId, "workflow", "Supervisor", 5,
                            role + " 需要人工审核",
                            "返修达到上限(" + maxRevisionRounds + ")仍未通过质量检查", 65);
                } else {
                    log.warn("[Supervisor] Manual review fallback is disabled. " +
                            "Allowing potentially low-quality artifact to proceed for role: {}", role);
                }
            }
        }

        // 如果有需要人工审核的角色，更新黑板状态
        if (!manualReviewRoles.isEmpty()) {
            blackboard.put("manual_review_required_roles", manualReviewRoles);
            blackboard.put("workflow_status", "MANUAL_REVIEW_REQUIRED");
            log.info("[Supervisor] Total {} specialist(s) marked for manual review: {}",
                    manualReviewRoles.size(), String.join(", ", manualReviewRoles));
        }

        return totalRevisions;
    }

    /**
     * 构建 Fallback 原因说明
     */
    private String buildFallbackReason(AgentResult criticResult, String role) {
        StringBuilder reason = new StringBuilder();
        reason.append("Agent ").append(role).append(" 在 ")
                .append(orchestrationProps.getMaxRevisionRounds()).append(" 轮返修后仍无法通过 Critic 审查。");

        if (criticResult.metadata() != null) {
            Object verdict = criticResult.metadata().get("verdict");
            Object critique = criticResult.metadata().get("critique");
            Object factualityScore = criticResult.metadata().get("factualityScore");

            if (verdict != null) {
                reason.append(" 最终审查结果: ").append(verdict).append(".");
            }
            if (factualityScore != null) {
                reason.append(" 事实性得分: ").append(factualityScore).append(".");
            }
            if (critique != null) {
                reason.append(" 审查意见: ").append(critique);
            }
        }
        return reason.toString();
    }

    /**
     * 记录需要人工审核的日志到 AgentExecutionLog
     */
    private void logManualReviewRequired(String runId, AgentTask task, String role,
                                         AgentResult criticResult, AgentResult specialistResult, int revisionRounds) {
        try {
            AgentExecutionLog logEntry = new AgentExecutionLog();
            logEntry.setSessionId(runId);
            logEntry.setAgentRole("SupervisorAgent -> " + role);
            logEntry.setStatus("MANUAL_REVIEW_REQUIRED");
            logEntry.setArtifactType(artifactTypeForRole(role));
            logEntry.setActionName("CRITIC_REVISION_EXHAUSTED");

            StringBuilder thought = new StringBuilder();
            thought.append("Critic 审查在 ").append(revisionRounds)
                    .append(" 轮返修后仍无法通过质量检查。\n");
            thought.append("审查结果: ").append(criticResult.output()).append("\n");

            if (criticResult.metadata() != null) {
                Object factualityScore = criticResult.metadata().get("factualityScore");
                if (factualityScore != null) {
                    thought.append("事实性得分: ").append(factualityScore).append("\n");
                }
            }
            logEntry.setThought(thought.toString());

            String actionInput = "Specialist output length: " +
                    (specialistResult.output() != null ? specialistResult.output().length() : 0);
            logEntry.setActionInput(actionInput);

            logEntry.setObservation(buildFallbackReason(criticResult, role));
            logEntry.setFallbackReason(buildFallbackReason(criticResult, role));
            logEntry.setGmtCreated(LocalDateTime.now());
            logEntry.setGmtModified(LocalDateTime.now());

            executionLogRepository.save(logEntry);

            log.info("[Supervisor] Manual review requirement logged to AgentExecutionLog for role: {}, log id: {}",
                    role, logEntry.getId());
        } catch (Exception e) {
            log.error("[Supervisor] Failed to log manual review requirement for role: {}", role, e);
        }
    }

    private Map<String, Object> revisionInputFor(
            AgentTask task,
            AgentResult previousResult,
            AgentResult criticResult,
            String role,
            int revisionRound
    ) {
        Map<String, Object> input = new HashMap<>(task.input());
        input.put("revisionTarget", role);
        input.put("revisionRound", revisionRound);
        input.put("previousContent", previousResult.output() != null ? previousResult.output() : "");
        input.put("revisionInstruction", revisionInstruction(criticResult));
        return input;
    }

    private String revisionInstruction(AgentResult criticResult) {
        if (criticResult == null || criticResult.metadata() == null) {
            return "Revise the artifact according to the critic feedback.";
        }
        Object report = criticResult.metadata().get("criticReport");
        if (report instanceof Map<?, ?> map) {
            Object instruction = map.get("revisionInstruction");
            if (instruction != null && !instruction.toString().isBlank()) {
                return instruction.toString();
            }
        }
        Object critique = criticResult.metadata().get("critique");
        return critique != null && !critique.toString().isBlank()
                ? critique.toString()
                : "Revise the artifact according to the critic feedback.";
    }

    /**
     * 构建需要人工审核的 AgentResult
     */
    private AgentResult buildManualReviewResult(String runId, WorkflowState state,
                                                   List<String> manualReviewRoles,
                                                   int totalSpecialists,
                                                   int totalRevisionRounds,
                                                   SharedBlackboard blackboard) {
        Map<String, Object> finalMeta = new HashMap<>();
        finalMeta.put("runId", runId);
        finalMeta.put("workflowState", state.name());
        finalMeta.put("revisionRounds", totalRevisionRounds);
        finalMeta.put("specialistsExecuted", totalSpecialists);
        finalMeta.put("manualReviewRequired", true);
        finalMeta.put("manualReviewRoles", manualReviewRoles);
        finalMeta.put("parallelDispatch", true);
        finalMeta.put("distributedOrchestration", distributedHandoffExecutor.isActive());
        finalMeta.put("collaborationModel", distributedHandoffExecutor.isActive()
                ? "Redis Stream handoff + AgentWorker autonomous execution"
                : "parallel specialists + PathAgent consumes SharedBlackboard peer summaries");
        finalMeta.put("peerSummaryRoles", consumedPeerRoles(blackboard, "PathAgent"));
        finalMeta.put("messageBusRole", distributedHandoffExecutor.isActive()
                ? "redis-stream dispatch with worker execution"
                : "audit handoff trace + in-process execution");
        finalMeta.put("blackboardKeys", blackboard.keySet());

        // 收集所有需要人工审核的详细信息
        Map<String, Object> manualReviewDetails = new HashMap<>();
        for (String role : manualReviewRoles) {
            Map<String, Object> details = new HashMap<>();
            details.put("revisionRounds", blackboard.get(role + "_revision_round"));
            details.put("reason", blackboard.get(role + "_manual_review_reason"));

            Object criticResult = blackboard.get(role + "_critic");
            if (criticResult instanceof AgentResult result && result.metadata() != null) {
                details.put("factualityScore", result.metadata().get("factualityScore"));
                details.put("verdict", result.metadata().get("verdict"));
                details.put("critique", result.metadata().get("critique"));
            }
            manualReviewDetails.put(role, details);
        }
        finalMeta.put("manualReviewDetails", manualReviewDetails);

        String output = String.format(
                "多智能体编排流程因质量检查失败需要人工审核。%d 个 Specialist 在 %d 轮返修后仍无法通过 Critic 审查: %s",
                manualReviewRoles.size(),
                totalRevisionRounds,
                String.join(", ", manualReviewRoles)
        );

        log.info("[Supervisor] Building MANUAL_REVIEW_REQUIRED result for runId: {}, roles: {}",
                runId, String.join(", ", manualReviewRoles));

        // 创建包含人工审核交接的 handoffs
        List<AgentHandoff> handoffs = List.of(
                new AgentHandoff("ManualReviewAgent", Map.of(
                        "manualReviewRoles", manualReviewRoles,
                        "runId", runId,
                        "details", manualReviewDetails
                ))
        );

        return new AgentResult(
                false, // success = false，因为需要人工干预
                output,
                List.of(),
                finalMeta,
                handoffs
        );
    }

    private Map<String, Object> collectCriticReports(List<String> specialists, SharedBlackboard blackboard) {
        Map<String, Object> reports = new LinkedHashMap<>();
        for (String role : specialists) {
            Object report = blackboard.get(role + "_critic");
            if (report instanceof AgentResult result) {
                reports.put(role, result.metadata());
            }
        }
        return reports;
    }

    private String artifactTypeForRole(String role) {
        return switch (role) {
            case "DocAgent" -> "HANDOUT";
            case "QuizAgent" -> "QUIZ";
            case "MindMapAgent" -> "MINDMAP";
            case "ReadingAgent" -> "EXTENDED_READING";
            case "VideoScriptAgent" -> "VISUALIZATION";
            case "PathAgent" -> "LEARNING_PATH";
            case "CodingAgent" -> "CODE_PRACTICE";
            case "VisualizationAgent" -> "VISUALIZATION";
            default -> "UNKNOWN";
        };
    }

    private Map<String, Object> criticInputFor(
            AgentTask task,
            SharedBlackboard blackboard,
            AgentResult specialistResult,
            String role
    ) {
        Map<String, Object> criticInput = new HashMap<>(task.input());
        criticInput.put("content", specialistResult.output() != null ? specialistResult.output() : "");
        criticInput.put("topic", blackboard.getCurrentTopic() != null ? blackboard.getCurrentTopic() : task.input().get("topic"));
        criticInput.put("reviseTarget", role);
        criticInput.put("artifactAgent", role);
        String topic = String.valueOf(criticInput.get("topic"));
        String artifactType = artifactTypeForRole(role);
        RagRetrievalResult rag = ragRetrievalService.retrieveForTask(
                com.visionary.agent.AgentTaskType.RESOURCE_GENERATION,
                topic + " " + artifactType
        );
        criticInput.put("ragEvidenceBlock", rag.toCitationInstructionBlock());
        criticInput.put("ragCitationIds", rag.citations().stream().map(c -> c.citationId()).toList());
        return criticInput;
    }

    private boolean needsRevision(AgentResult criticResult) {
        if (criticResult == null) {
            return false;
        }
        String verdict = String.valueOf(criticResult.metadata().getOrDefault("verdict", "PASS"));
        if ("REVISE".equalsIgnoreCase(verdict) || "REJECT".equalsIgnoreCase(verdict)) {
            return true;
        }
        Object criticReport = criticResult.metadata().get("criticReport");
        if (criticReport instanceof Map<?, ?> report) {
            Object needs = report.get("needsRevision");
            return Boolean.TRUE.equals(needs);
        }
        return false;
    }

    private boolean shouldBreakOnGovernance(
            String runId,
            String role,
            int revisionRound,
            double previousCompositeScore,
            AgentResult criticResult,
            SharedBlackboard blackboard
    ) {
        try {
            Optional<Long> artifactId = findArtifactIdForRole(runId, role);
            if (artifactId.isEmpty()) {
                return false;
            }
            Object stored = blackboard.get(role + "_result");
            if (stored instanceof AgentResult draft && draft.output() != null) {
                persistDraftForGovernance(artifactId.get(), draft.output());
            }
            double llmScore = extractFactualityScore(criticResult) * 100.0D;
            BreakerDecision decision = governanceTraceService.recordAndEvaluate(
                    String.valueOf(artifactId.get()),
                    revisionRound,
                    llmScore,
                    previousCompositeScore,
                    String.valueOf(criticResult.metadata().getOrDefault("critique", ""))
            );
            if (!decision.shouldContinue()) {
                log.warn(
                        "[GovernanceBreaker] artifactId={}, round={}, role={}, decisionCode={}, reason={}",
                        artifactId.get(),
                        revisionRound,
                        role,
                        decision.decisionCode(),
                        decision.reason()
                );
                return true;
            }
        } catch (Exception ex) {
            log.warn(
                    "[GovernanceBreaker] evaluation skipped, legacy bounded loop continues: role={}, round={}, error={}",
                    role,
                    revisionRound,
                    ex.getMessage()
            );
        }
        return false;
    }

    private boolean registerBestRevisionSnapshot(
            String runId,
            String role,
            GovernanceBestSolutionTracker bestTracker,
            AgentResult specialistResult,
            AgentResult criticResult
    ) {
        return findArtifactIdForRole(runId, role)
                .flatMap(artifactRepository::findById)
                .map(artifact -> {
                    if (specialistResult.output() != null) {
                        artifact.setContentMarkdown(specialistResult.output());
                    }
                    double llmScore = extractFactualityScore(criticResult) * 100.0D;
                    return bestTracker.registerIfBetter(artifact, llmScore);
                })
                .orElse(false);
    }

    private double resolveCompositeScore(String runId, String role, AgentResult criticResult) {
        return findArtifactIdForRole(runId, role)
                .flatMap(artifactRepository::findById)
                .map(artifact -> compositeScoreCalculator.computeCompositeScore(
                        artifact,
                        extractFactualityScore(criticResult) * 100.0D
                ))
                .orElse(extractFactualityScore(criticResult) * 100.0D);
    }

    private Optional<Long> findArtifactIdForRole(String runId, String role) {
        String typeName = artifactTypeForRole(role);
        if ("UNKNOWN".equals(typeName)) {
            return Optional.empty();
        }
        try {
            GeneratedArtifact.ArtifactType type = GeneratedArtifact.ArtifactType.valueOf(typeName);
            return artifactRepository.findByRunIdOrderByIdAsc(runId).stream()
                    .filter(artifact -> artifact.getArtifactType() == type)
                    .map(GeneratedArtifact::getId)
                    .findFirst();
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private void persistDraftForGovernance(Long artifactId, String draftContent) {
        artifactRepository.findById(artifactId).ifPresent(artifact -> {
            artifact.setContentMarkdown(draftContent);
            artifactRepository.save(artifact);
        });
    }

    private double extractFactualityScore(AgentResult criticResult) {
        if (criticResult == null || criticResult.metadata() == null) {
            return 0.5D;
        }
        Object score = criticResult.metadata().get("factualityScore");
        if (score instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(score));
        } catch (NumberFormatException ex) {
            return 0.5D;
        }
    }

    private Map<String, Object> mergeTaskInput(Map<String, Object> baseInput, Map<String, Object> overrides) {
        Map<String, Object> merged = new HashMap<>();
        if (baseInput != null) {
            merged.putAll(baseInput);
        }
        if (overrides != null && !overrides.isEmpty()) {
            merged.putAll(overrides);
        }
        return merged;
    }

    private AgentResult handoffAndWait(String targetRole, AgentTask originalTask, AgentContext ctx, String runId) {
        return handoffAndWait(targetRole, originalTask, originalTask.input(), ctx, runId);
    }

    private AgentResult handoffAndWait(
            String targetRole,
            AgentTask originalTask,
            Map<String, Object> input,
            AgentContext ctx,
            String runId
    ) {
        Agent target = resolveAgent(targetRole);
        if (target == null) {
            log.warn("Agent {} not registered", targetRole);
            return new AgentResult(false, "Agent not found", List.of(), Map.of(), List.of());
        }

        String subTaskId = UUID.randomUUID().toString();
        AgentTask subTask = new AgentTask(
                subTaskId,
                originalTask.type(),
                input,
                List.of(targetRole)
        );

        messageBus.publish(new AgentMessage(
                UUID.randomUUID().toString(),
                "Supervisor",
                targetRole,
                "HANDOFF",
                runId,
                Map.of(
                        "taskId", subTaskId,
                        "type", originalTask.type(),
                        "input", input
                ),
                java.time.Instant.now()
        ));

        AgentResult result;
        if (distributedHandoffExecutor.isActive()) {
            result = distributedHandoffExecutor.executeHandoff(targetRole, subTask, ctx, runId);
        } else {
            result = target.execute(subTask, ctx);
        }

        messageBus.publish(new AgentMessage(
                UUID.randomUUID().toString(),
                targetRole,
                "Supervisor",
                "RESULT",
                runId,
                Map.of(
                        "taskId", subTaskId,
                        "success", result.success(),
                        "verdict", result.metadata().getOrDefault("verdict", "UNKNOWN")
                ),
                java.time.Instant.now()
        ));

        return result;
    }

    private Agent resolveAgent(String targetRole) {
        if (agentRegistry == null || targetRole == null || targetRole.isBlank()) {
            return null;
        }
        Agent direct = agentRegistry.get(targetRole);
        if (direct != null) {
            return direct;
        }
        String beanName = Character.toLowerCase(targetRole.charAt(0)) + targetRole.substring(1);
        Agent byBeanName = agentRegistry.get(beanName);
        if (byBeanName != null) {
            return byBeanName;
        }
        return agentRegistry.values().stream()
                .filter(Objects::nonNull)
                .filter(agent -> targetRole.equals(agent.getRole()))
                .findFirst()
                .orElse(null);
    }

    private void emitProgress(
            AgentContext context,
            String runId,
            String phase,
            String agentName,
            int stepOrder,
            String message,
            String detail,
            int progressPercent
    ) {
        if (context == null || context.metadata() == null) {
            return;
        }
        Object raw = context.metadata().get("progressListener");
        if (!(raw instanceof ResourceGenerationProgressListener listener)) {
            return;
        }
        try {
            listener.onProgress(new ResourceGenerationProgressEvent(
                    runId, phase, agentName, stepOrder, message, detail, progressPercent
            ));
        } catch (Exception e) {
            log.warn("Failed to emit progress event: {}", e.getMessage());
            // non-blocking progress
        }
    }
}
