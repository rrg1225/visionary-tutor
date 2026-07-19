package com.visionary.agent.worker;

import com.visionary.agent.core.*;
import com.visionary.config.AgentOrchestrationProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DistributedHandoffExecutor {

    private final AgentOrchestrationProperties properties;
    private final MessageBus messageBus;
    private final AgentHandoffStore handoffStore;
    private final AgentWorkerService agentWorkerService;
    private final StringRedisTemplate redisTemplate;

    public boolean isActive() {
        if (!properties.isDistributed()) {
            return false;
        }
        try {
            redisTemplate.getConnectionFactory().getConnection().ping();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public AgentResult executeHandoff(
            String targetRole,
            AgentTask subTask,
            AgentContext ctx,
            String runId
    ) {
        if (!isActive()) {
            throw new IllegalStateException("Distributed handoff requested but Redis/bus unavailable");
        }

        handoffStore.clearResult(subTask.taskId());
        handoffStore.saveContext(new AgentHandoffStore.HandoffContext(
                runId,
                subTask.taskId(),
                subTask.type(),
                targetRole,
                subTask.input(),
                ctx.blackboard().getCurrentTopic(),
                ctx.blackboard().getLearnerProfileSnapshot()
        ));

        messageBus.publish(new AgentMessage(
                UUID.randomUUID().toString(),
                "Supervisor",
                targetRole,
                "HANDOFF",
                runId,
                Map.of(
                        "taskId", subTask.taskId(),
                        "type", subTask.type(),
                        "input", subTask.input(),
                        "distributed", true
                ),
                java.time.Instant.now()
        ));

        long deadline = System.currentTimeMillis() + properties.getHandoffTimeoutMs();
        while (System.currentTimeMillis() < deadline) {
            agentWorkerService.processPendingForRole(targetRole, 1);
            var result = handoffStore.getResult(subTask.taskId());
            if (result.isPresent()) {
                AgentResult agentResult = result.get();
                messageBus.publish(new AgentMessage(
                        UUID.randomUUID().toString(),
                        targetRole,
                        "Supervisor",
                        "RESULT",
                        runId,
                        Map.of(
                                "taskId", subTask.taskId(),
                                "success", agentResult.success(),
                                "distributed", true
                        ),
                        java.time.Instant.now()
                ));
                return agentResult;
            }
            try {
                Thread.sleep(Math.max(20L, properties.getWorker().getPollIntervalMs()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        return new AgentResult(
                false,
                targetRole + " distributed handoff timed out",
                java.util.List.of(),
                Map.of("distributed", true, "timeoutMs", properties.getHandoffTimeoutMs()),
                java.util.List.of()
        );
    }
}
