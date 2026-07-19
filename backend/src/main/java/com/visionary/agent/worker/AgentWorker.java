package com.visionary.agent.worker;

import com.visionary.config.AgentOrchestrationProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "visionary.agent.worker.enabled", havingValue = "true")
public class AgentWorker {

    private final AgentWorkerService agentWorkerService;
    private final AgentOrchestrationProperties properties;

    @Scheduled(fixedDelayString = "${visionary.agent.worker.poll-interval-ms:500}")
    public void pollAllAgents() {
        for (String role : agentWorkerService.registeredRoles()) {
            int processed = agentWorkerService.processPendingForRole(role, 5);
            if (processed > 0) {
                log.debug("[AgentWorker] processed {} messages for {}", processed, role);
            }
        }
    }
}
