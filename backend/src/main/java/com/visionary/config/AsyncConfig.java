package com.visionary.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务配置。
 * 为媒体生成轮询等耗时/高并发操作提供独立的线程池，
 * 避免阻塞主线程。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 媒体生成任务执行器。
     * 用于视频/图像生成任务的轮询和异步处理。
     */
    @Bean(name = "mediaGenerationTaskExecutor")
    public Executor mediaGenerationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 核心线程数
        executor.setCorePoolSize(4);
        // 最大线程数
        executor.setMaxPoolSize(16);
        // 队列容量
        executor.setQueueCapacity(100);
        // 线程活跃时间（秒）
        executor.setKeepAliveSeconds(60);
        // 线程名前缀
        executor.setThreadNamePrefix("media-gen-");
        // 拒绝策略：由调用线程执行（确保不丢失任务）
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 优雅关闭
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
