package com.visionary.exception;

import com.visionary.agent.core.AgentResult;
import lombok.Getter;

import java.util.List;

/**
 * Agent 编排异常 - 用于 ReAct 模式熔断和降级处理。
 * <p>
 * 当 ReActSupervisorAdapter 检测到死循环或达到最大迭代次数时抛出此异常，
 * 通知上层调用方（MultiAgentResourceService）切换到 legacy 降级模式。
 */
@Getter
public class AgentOrchestrationException extends RuntimeException {

    /**
     * 熔断原因类型
     */
    public enum CircuitBreakerReason {
        MAX_ITERATIONS_REACHED("达到最大迭代次数限制"),
        REPEATED_FAILED_TOOL_CALLS("连续多次调用失败工具"),
        INFINITE_LOOP_DETECTED("检测到无限循环"),
        TOOL_EXECUTION_TIMEOUT("工具执行超时"),
        REACT_UNAVAILABLE("ReAct 模式不可用");

        private final String description;

        CircuitBreakerReason(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    private final CircuitBreakerReason reason;
    private final String runId;
    private final int iterationsCompleted;
    private final List<String> failedToolAttempts;
    private final AgentResult partialResult;

    public AgentOrchestrationException(CircuitBreakerReason reason, String runId, int iterationsCompleted,
                                      List<String> failedToolAttempts, AgentResult partialResult) {
        super(String.format("[AgentOrchestrationException] %s after %d iterations. RunId: %s",
                reason.getDescription(), iterationsCompleted, runId));
        this.reason = reason;
        this.runId = runId;
        this.iterationsCompleted = iterationsCompleted;
        this.failedToolAttempts = failedToolAttempts;
        this.partialResult = partialResult;
    }

    public AgentOrchestrationException(CircuitBreakerReason reason, String runId, String message) {
        super(String.format("[AgentOrchestrationException] %s - %s. RunId: %s",
                reason.getDescription(), message, runId));
        this.reason = reason;
        this.runId = runId;
        this.iterationsCompleted = 0;
        this.failedToolAttempts = List.of();
        this.partialResult = null;
    }

    /**
     * 判断是否包含部分结果（可用于降级时保存已生成的资源）
     */
    public boolean hasPartialResult() {
        return partialResult != null && partialResult.success();
    }

    /**
     * 获取熔断摘要，用于日志记录
     */
    public String getCircuitSummary() {
        return String.format("CircuitBreaker[%s] runId=%s, iterations=%d, failedAttempts=%d",
                reason, runId, iterationsCompleted,
                failedToolAttempts != null ? failedToolAttempts.size() : 0);
    }
}
