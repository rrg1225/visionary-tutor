package com.visionary.agent.worker;

import com.visionary.agent.core.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentWorkerService {

    private final MessageBus messageBus;
    private final Map<String, Agent> agentRegistry;
    private final Map<String, Tool> toolRegistry;
    private final AgentHandoffStore handoffStore;

    public boolean processMessage(AgentMessage message) {
        if (message == null || message.payload() == null) {
            return false;
        }
        String targetRole = message.toRole();
        Agent agent = resolveAgent(targetRole);
        if (agent == null) {
            log.warn("[AgentWorkerService] no agent registered for {}", targetRole);
            return false;
        }

        String subTaskId = String.valueOf(message.payload().getOrDefault("taskId", message.id()));
        AgentHandoffStore.HandoffContext context = handoffStore.getContext(subTaskId).orElse(null);

        Map<String, Object> input = context != null && context.input() != null
                ? context.input()
                : message.payload();
        String taskType = context != null ? context.taskType() : String.valueOf(message.payload().getOrDefault("type", "RESOURCE_GENERATION"));
        String runId = context != null ? context.runId() : message.taskId();

        SharedBlackboard blackboard = new SharedBlackboard();
        blackboard.setRunId(runId);
        if (context != null) {
            if (context.currentTopic() != null) {
                blackboard.setCurrentTopic(context.currentTopic());
            }
            if (context.learnerProfileSnapshot() != null) {
                blackboard.updateProfileSnapshot(context.learnerProfileSnapshot());
            }
        }

        AgentTask task = new AgentTask(subTaskId, taskType, input, List.of(targetRole));
        AgentContext agentContext = new AgentContext(blackboard, toolRegistry, messageBus, runId, Map.of());

        try {
            AgentResult result = agent.execute(task, agentContext);
            handoffStore.saveResult(subTaskId, result);
            messageBus.ack(message.id());
            log.info("[AgentWorkerService] {} executed distributed task {} runId={}", targetRole, subTaskId, runId);
            return true;
        } catch (Exception e) {
            handoffStore.saveResult(subTaskId, new AgentResult(
                    false,
                    targetRole + " failed: " + e.getMessage(),
                    List.of(),
                    Map.of("error", e.getMessage()),
                    List.of()
            ));
            log.error("[AgentWorkerService] {} failed task {}: {}", targetRole, subTaskId, e.getMessage());
            return false;
        }
    }

    public int processPendingForRole(String role, int maxMessages) {
        List<AgentMessage> messages = messageBus.poll(role, maxMessages);
        int processed = 0;
        for (AgentMessage message : messages) {
            if (processMessage(message)) {
                processed++;
            }
        }
        return processed;
    }

    public java.util.Set<String> registeredRoles() {
        return agentRegistry.keySet();
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
}
