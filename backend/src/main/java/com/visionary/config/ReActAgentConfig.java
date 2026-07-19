package com.visionary.config;

import com.visionary.agent.ReActSupervisorAdapter;
import com.visionary.agent.SupervisorAgent;
import com.visionary.agent.core.Agent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Selects the real supervisor used by resource generation.
 */
@Slf4j
@Configuration
public class ReActAgentConfig {

    @Value("${agent.mode:react}")
    private String agentMode;

    @Bean(name = "activeSupervisorAgent")
    @Primary
    public Agent activeSupervisorAgent(
            SupervisorAgent legacySupervisor,
            ReActSupervisorAdapter reactSupervisor) {

        log.info("[ReActConfig] Agent mode configured: {}", agentMode);

        if ("react".equalsIgnoreCase(agentMode)) {
            if (!reactSupervisor.isReActAvailable()) {
                log.warn("[ReActConfig] ReAct requested but DeepSeek ChatModel is unavailable; using the deterministic supervisor instead.");
                return legacySupervisor;
            }
            log.info("[ReActConfig] ReAct supervisor is active.");
            return reactSupervisor;
        }

        log.info("[ReActConfig] Legacy supervisor is active.");
        return legacySupervisor;
    }
}
