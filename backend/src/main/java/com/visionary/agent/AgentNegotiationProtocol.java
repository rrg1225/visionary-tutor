package com.visionary.agent;

import com.visionary.agent.core.AgentContext;
import com.visionary.agent.core.AgentMessage;
import com.visionary.agent.core.AgentMessageType;
import com.visionary.agent.core.MessageBus;
import com.visionary.agent.core.SharedBlackboard;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Structured agent-to-agent negotiation on MessageBus + SharedBlackboard.
 * Negotiation adjusts constraints and alignment context; generation quality gates stay unchanged.
 */
public final class AgentNegotiationProtocol {

    public static final String NEGOTIATION_PHASE_KEY = "negotiationPhase";
    public static final String PHASE_OUTLINE = "OUTLINE";
    public static final String PHASE_FINAL = "FINAL";

    public static final String COORDINATOR_ROLE = "Supervisor";

    private AgentNegotiationProtocol() {
    }

    public static boolean isOutlinePhase(Map<String, Object> taskInput) {
        return taskInput != null && PHASE_OUTLINE.equals(String.valueOf(taskInput.get(NEGOTIATION_PHASE_KEY)));
    }

    public static boolean isFinalPhase(Map<String, Object> taskInput) {
        if (taskInput == null || !taskInput.containsKey(NEGOTIATION_PHASE_KEY)) {
            return true;
        }
        return PHASE_FINAL.equals(String.valueOf(taskInput.get(NEGOTIATION_PHASE_KEY)));
    }

    public static void publishExecutionPlan(AgentContext context, Collection<String> targetRoles, String planSummary) {
        if (context == null || targetRoles == null || targetRoles.isEmpty()) {
            return;
        }
        SharedBlackboard blackboard = context.blackboard();
        if (blackboard != null) {
            blackboard.put("negotiation_execution_plan", planSummary);
            blackboard.incrementDebateRound();
            recordDebate(blackboard, "PlannerAgent", "发布协作计划: " + truncate(planSummary, 240));
        }
        Map<String, Object> payload = Map.of(
                "planSummary", planSummary == null ? "" : planSummary,
                "targetRoles", List.copyOf(targetRoles)
        );
        for (String role : targetRoles) {
            publish(context, "PlannerAgent", role, AgentMessageType.PROPOSE, context.runId(), payload);
        }
    }

    public static void publishOutlineProposal(AgentContext context, String sourceRole, String outline) {
        if (context == null || sourceRole == null) {
            return;
        }
        SharedBlackboard blackboard = context.blackboard();
        if (blackboard != null) {
            blackboard.put(sourceRole + "_outline", outline == null ? "" : outline);
            blackboard.incrementDebateRound();
            recordDebate(blackboard, sourceRole, "OUTLINE 提案 → 全体协作者: " + truncate(outline, 200));
        }
        Map<String, Object> payload = Map.of(
                "outline", outline == null ? "" : outline,
                "sourceRole", sourceRole
        );
        publish(context, sourceRole, COORDINATOR_ROLE, AgentMessageType.OUTLINE_PROPOSAL, context.runId(), payload);
        publish(context, sourceRole, "PathAgent", AgentMessageType.OUTLINE_PROPOSAL, context.runId(), payload);
    }

    public static void publishArtifactReady(AgentContext context, String sourceRole, Map<String, Object> payload) {
        publish(context, sourceRole, COORDINATOR_ROLE, AgentMessageType.ARTIFACT_READY, context.runId(), payload);
    }

    public static void publishCritiqueRequest(
            AgentContext context,
            String targetRole,
            String verdict,
            String reflectionReason,
            boolean needsRevision
    ) {
        SharedBlackboard blackboard = context != null ? context.blackboard() : null;
        if (blackboard != null) {
            blackboard.incrementDebateRound();
            recordDebate(blackboard, "CriticAgent",
                    "CRITIQUE → " + targetRole + ": " + verdict + " · " + truncate(reflectionReason, 160));
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("verdict", verdict);
        payload.put("reflectionReason", reflectionReason == null ? "" : reflectionReason);
        payload.put("needsRevision", needsRevision);
        publish(context, "CriticAgent", targetRole, AgentMessageType.CRITIQUE_REQUEST, context.runId(), payload);
        publish(context, "CriticAgent", COORDINATOR_ROLE, AgentMessageType.CRITIQUE_RESPONSE, context.runId(), payload);
    }

    public static void publishRevisionRequired(
            AgentContext context,
            String targetRole,
            String revisionInstruction,
            int revisionRound
    ) {
        SharedBlackboard blackboard = context != null ? context.blackboard() : null;
        if (blackboard != null) {
            recordDebate(blackboard, "CriticAgent",
                    "REVISION → " + targetRole + " (round " + revisionRound + "): "
                            + truncate(revisionInstruction, 160));
        }
        Map<String, Object> payload = Map.of(
                "revisionInstruction", revisionInstruction == null ? "" : revisionInstruction,
                "revisionRound", revisionRound
        );
        publish(context, "CriticAgent", targetRole, AgentMessageType.REVISION_REQUIRED, context.runId(), payload);
    }

    public static void publishConsensus(AgentContext context, String summary, String verdict) {
        SharedBlackboard blackboard = context != null ? context.blackboard() : null;
        if (blackboard != null) {
            recordDebate(blackboard, "ReviewAgent", "CONSENSUS: " + verdict + " · " + truncate(summary, 200));
        }
        Map<String, Object> payload = Map.of(
                "summary", summary == null ? "" : summary,
                "verdict", verdict == null ? "PASS" : verdict
        );
        publish(context, "ReviewAgent", COORDINATOR_ROLE, AgentMessageType.CONSENSUS, context.runId(), payload);
    }

    private static void publish(
            AgentContext context,
            String fromRole,
            String toRole,
            AgentMessageType type,
            String taskId,
            Map<String, Object> payload
    ) {
        if (context == null || context.messageBus() == null) {
            return;
        }
        MessageBus bus = context.messageBus();
        bus.publish(new AgentMessage(
                UUID.randomUUID().toString(),
                type,
                fromRole,
                toRole,
                payload == null ? Map.of() : payload,
                taskId,
                Instant.now()
        ));
    }

    private static void recordDebate(SharedBlackboard blackboard, String actor, String message) {
        blackboard.addDebateEntry("[" + actor + "] " + message);
    }

    private static String truncate(String value, int max) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() <= max ? compact : compact.substring(0, max) + "...";
    }
}
