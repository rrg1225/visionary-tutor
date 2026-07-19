package com.visionary.agent;

import com.visionary.agent.core.Agent;
import com.visionary.agent.core.AgentContext;
import com.visionary.agent.core.AgentHandoff;
import com.visionary.agent.core.AgentResult;
import com.visionary.agent.core.AgentTask;
import com.visionary.service.CriticCoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CriticAgent - 质量审查 Agent（适配器模式）
 * <p>
 * 职责：
 * 1. 实现 Agent 接口，接收审查任务
 * 2. 委托 CriticCoreService 执行核心审查逻辑
 * 3. 将审查结果转换为 AgentResult 格式
 * 4. 管理黑板状态（revision_round, reflection_reason 等）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CriticAgent implements Agent {

    private final CriticCoreService criticCoreService;

    @Override
    public String getRole() {
        return "CriticAgent";
    }

    @Override
    public Set<String> getSupportedTools() {
        return Set.of("CitationValidatorTool");
    }

    @Override
    public AgentResult execute(AgentTask task, AgentContext context) {
        String content = (String) task.input().getOrDefault("content", "");
        String topic = (String) task.input().getOrDefault("topic", "");
        String profile = (String) task.input().getOrDefault("learnerProfileSnapshot", "");
        String ragBlock = (String) task.input().getOrDefault("ragEvidenceBlock", "");

        int revisionRound = getCurrentRevisionRound(context);

        log.info("[CriticAgent] Executing review: topic='{}', revisionRound={}", topic, revisionRound);

        // 委托核心服务执行审查
        CriticCoreService.CriticResult result = criticCoreService.reviewContent(
                topic, content, profile, ragBlock, revisionRound
        );

        // 更新黑板状态
        updateBlackboard(context, result);

        String targetRole = String.valueOf(task.input().getOrDefault("artifactAgent",
                task.input().getOrDefault("reviseTarget", "Supervisor")));
        AgentNegotiationProtocol.publishCritiqueRequest(
                context,
                targetRole,
                result.verdict(),
                result.reflectionReason(),
                result.needsRevision()
        );
        if (result.needsRevision()) {
            AgentNegotiationProtocol.publishRevisionRequired(
                    context,
                    targetRole,
                    result.reflectionReason(),
                    result.nextRevisionRound()
            );
        }

        // 构建 AgentResult
        return buildAgentResult(result, revisionRound);
    }

    private int getCurrentRevisionRound(AgentContext context) {
        if (context.blackboard() == null) return 0;
        Object round = context.blackboard().get("critic.revision_round");
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

    private void updateBlackboard(AgentContext context, CriticCoreService.CriticResult result) {
        if (context.blackboard() == null) return;

        context.blackboard().put("critic.reflection_reason", result.reflectionReason());
        context.blackboard().put("critic.last_verdict", result.verdict());
        context.blackboard().put("critic.factuality_score", result.factualityScore());
        context.blackboard().put("critic.revision_round", result.nextRevisionRound());

        if (result.factualityScore() < 0.75) {
            context.blackboard().put("critic.trigger_replan", true);
        }
    }

    private AgentResult buildAgentResult(CriticCoreService.CriticResult result, int currentRound) {
        boolean needsRevision = result.needsRevision();

        Map<String, Object> meta = Map.of(
                "verdict", result.verdict(),
                "critique", result.critique(),
                "reflection_reason", result.reflectionReason(),
                "missingCitationIds", result.missingCitationIds(),
                "factualErrors", result.factualErrors(),
                "factualityScore", result.factualityScore(),
                "hallucinationLog", result.hallucinationLog(),
                "needsRevision", needsRevision,
                "nextRevisionRound", result.nextRevisionRound()
        );

        String output = needsRevision
                ? "需要返修（第" + (currentRound + 1) + "轮）: " + result.reflectionReason()
                : "审查通过（Factuality=" + result.factualityScore() + ")";

        List<AgentHandoff> handoffs = needsRevision
                ? List.of(new AgentHandoff("PlannerAgent", Map.of(
                        "replan", true,
                        "reason", result.reflectionReason(),
                        "reflection_reason", result.reflectionReason(),
                        "revision_round", result.nextRevisionRound()
                )))
                : List.of();

        return new AgentResult(true, output, List.of(), meta, handoffs);
    }
}
