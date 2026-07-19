package com.visionary.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/** Dedicated bounded capacity for blocking model and tool calls. */
@Configuration
public class AgentExecutionConfig {

    @Bean(name = "agentSpecialistExecutor")
    public Executor agentSpecialistExecutor(
            @Value("${agent.generation.executor.core-size:4}") int coreSize,
            @Value("${agent.generation.executor.max-size:8}") int maxSize,
            @Value("${agent.generation.executor.queue-capacity:32}") int queueCapacity
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("agent-specialist-");
        executor.setCorePoolSize(Math.max(1, coreSize));
        executor.setMaxPoolSize(Math.max(coreSize, maxSize));
        executor.setQueueCapacity(Math.max(1, queueCapacity));
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
