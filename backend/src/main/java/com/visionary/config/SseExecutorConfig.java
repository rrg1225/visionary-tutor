package com.visionary.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Dedicated pool for SSE subscription work — avoids starving {@code ForkJoinPool.commonPool()}.
 */
@Configuration
public class SseExecutorConfig {

    @Bean(name = "sseStreamExecutor")
    public Executor sseStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("sse-stream-");
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(64);
        executor.setQueueCapacity(256);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setKeepAliveSeconds(60);
        executor.initialize();
        return executor;
    }
}
