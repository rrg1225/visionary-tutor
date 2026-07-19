package com.visionary.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent 编排配置属性 - 管理所有智能体相关的业务参数
 * 包括权重、阈值、重试次数、衰减因子等
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.orchestration")
public class AgentOrchestrationProperties {

    // ==================== BaseSpecialistAgent 配置 ====================

    /**
     * 标准执行流程配置（RAG + LLM 单次调用模式）
     */
    private int maxIterations = 4;

    // ==================== SupervisorAgent 配置 ====================

    /**
     * Critic 返修最大轮次
     */
    private int maxRevisionRounds = 5;

    /**
     * Specialist Agent 超时时间（秒）
     */
    private int specialistTimeoutSeconds = 90;

    /** End-to-end budget for one parallel specialist wave. */
    private int totalTimeoutSeconds = 120;

    // ==================== CriticAgent 配置 ====================

    /**
     * 事实性评分默认值（0.0-1.0）
     */
    private double factualityDefaultScore = 0.85;

    /**
     * 事实性评分低阈值（低于此值触发重规划）
     */
    private double factualityLowThreshold = 0.75;

    /**
     * 事实性评分失败降级值（调用异常时使用）
     */
    private double factualityFallbackScore = 0.6;

    // ==================== PlannerAgent 配置 ====================

    /**
     * 学生画像截断长度（字符数）
     */
    private int profileTruncateLength = 800;

    /**
     * 薄弱点信息截断长度（字符数）
     */
    private int weakPointsTruncateLength = 400;

    // ==================== PathAgent 配置 ====================

    /**
     * PathAgent 学生画像截断长度
     */
    private int pathAgentProfileTruncateLength = 400;

    // ==================== DocAgent 配置 ====================

    /**
     * DocAgent 学生画像截断长度
     */
    private int docAgentProfileTruncateLength = 500;

    // ==================== QuizAgent 配置 ====================

    /**
     * 默认题目数量
     */
    private int defaultQuestionCount = 6;

    /**
     * 最小题目数量
     */
    private int minQuestionCount = 3;

    /**
     * 最大题目数量
     */
    private int maxQuestionCount = 15;

    /**
     * QuizAgent 学生画像截断长度
     */
    private int quizAgentProfileTruncateLength = 500;

    // ==================== MindMapAgent 配置 ====================

    /**
     * MindMapAgent 学生画像截断长度
     */
    private int mindMapAgentProfileTruncateLength = 300;

    // ==================== 其他通用配置 ====================

    /**
     * 交叉审查最大输出长度（字符数）
     */
    private int crossCritiqueMaxLength = 200;

    /**
     * RAG 上下文默认截断长度
     */
    private int ragContextTruncateLength = 800;

    // ==================== Fallback 机制配置 ====================

    /**
     * 是否启用 Agent-to-Agent 协商（OUTLINE 提案 + 黑板对齐 + Critic 消息协议）
     */
    private boolean enableAgentNegotiation = true;

    /**
     * OUTLINE 协商阶段最大输出长度（字符）
     */
    private int outlineMaxLength = 320;

    /**
     * 是否启用人工审核 Fallback 机制
     * 当 Critic 返修达到上限仍失败时，将任务标记为 MANUAL_REVIEW_REQUIRED
     */
    private boolean enableManualReviewFallback = true;

    /**
     * Fallback 触发时的默认人工审核说明
     */
    private String manualReviewDefaultReason = "多次返修后仍无法通过自动审查，需要人工审核";

    // ==================== 分布式执行配置 ====================

    /**
     * 是否启用分布式执行（需要 Redis + MessageBus）
     */
    private boolean distributed = false;

    /**
     * Handoff 超时时间（毫秒）
     */
    private long handoffTimeoutMs = 120000;

    /**
     * Worker 配置
     */
    private Worker worker = new Worker();

    @Data
    public static class Worker {
        /**
         * Worker 轮询间隔（毫秒）
         */
        private long pollIntervalMs = 1000;
    }
}
