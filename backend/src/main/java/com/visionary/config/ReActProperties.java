package com.visionary.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * ReAct 监督器配置 — 迭代上限、并行度、超时与熔断开关。
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.react")
public class ReActProperties {

    /**
     * ReAct 主循环最大迭代次数（每轮通常调用一个 Specialist 工具）。
     */
    private int maxIterations = 16;

    /**
     * 并行工具执行线程池大小。
     */
    private int maxParallelTools = 4;

    /**
     * 单次工具调用超时（毫秒）。
     */
    private long toolTimeoutMs = 60_000L;

    /**
     * 是否在检测到死循环 / 重复失败时触发熔断。
     */
    private boolean fuseEnabled = true;
}
