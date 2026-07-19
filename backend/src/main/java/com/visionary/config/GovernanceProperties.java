package com.visionary.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 双层受控编排 — 审计治理层可调参数。
 */
@Data
@Component
@ConfigurationProperties(prefix = "visionary.agent.governance")
public class GovernanceProperties {

    /**
     * Critic 返修硬上限（含本轮）。达到后强制 HALT，防止 Agent 循环失控。
     */
    private int maxRevisionRounds = 5;

    /**
     * 边际增益阈值 Δ<sub>min</sub>（分）。当单轮得分提升低于该值时触发低边际效用熔断。
     */
    private double deltaThreshold = 3.0;

    /**
     * 收敛探测阈值（归一化到 0–1，默认 0.05 即 5 分）。连续两轮 Δ 均低于该值时提前熔断。
     */
    private double convergenceDeltaThreshold = 0.05;

    /** 客观规则分权重（composite = objectiveWeight × 客观 + llmWeight × LLM）。 */
    private double objectiveWeight = 0.4;

    /** LLM 主观分权重。 */
    private double llmWeight = 0.6;
}
