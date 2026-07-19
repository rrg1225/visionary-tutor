package com.visionary.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;
import org.slf4j.MDC;

import java.time.Instant;

/**
 * Agent 统一响应结构 - 不可变对象设计。
 * <p>使用 @Getter 而非 @Data 防止外部修改，保证响应对象的完整性。</p>
 * <p>requestId 从 MDC 自动获取，用于全链路追踪。</p>
 */
@Getter
@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentResponse<T> {

    private final String requestId;
    private final AgentTaskType taskType;
    private final AgentTaskType resolvedRoute;
    private final ResponseStatus status;
    private final String message;
    private final String errorCode;
    private final String provider;
    private final Long latencyMs;
    private final Instant timestamp;
    private final T data;

    public enum ResponseStatus {
        SUCCESS,
        FALLBACK,
        ERROR
    }

    private static final String TRACE_ID_KEY = "traceId";

    /**
     * 从 MDC 获取当前请求的 Trace ID，如果没有则生成临时 ID。
     */
    private static String getCurrentTraceId() {
        String traceId = MDC.get(TRACE_ID_KEY);
        return traceId != null ? traceId : "unknown";
    }

    public static <T> AgentResponse<T> success(
            AgentTaskType taskType,
            AgentTaskType resolvedRoute,
            String provider,
            long latencyMs,
            T data
    ) {
        return AgentResponse.<T>builder()
                .requestId(getCurrentTraceId())
                .taskType(taskType)
                .resolvedRoute(resolvedRoute)
                .status(ResponseStatus.SUCCESS)
                .message("ok")
                .provider(provider)
                .latencyMs(latencyMs)
                .timestamp(Instant.now())
                .data(data)
                .build();
    }

    public static <T> AgentResponse<T> fallback(
            AgentTaskType taskType,
            AgentTaskType resolvedRoute,
            String provider,
            long latencyMs,
            String message,
            T data
    ) {
        return AgentResponse.<T>builder()
                .requestId(getCurrentTraceId())
                .taskType(taskType)
                .resolvedRoute(resolvedRoute)
                .status(ResponseStatus.FALLBACK)
                .message(message)
                .provider(provider)
                .latencyMs(latencyMs)
                .timestamp(Instant.now())
                .data(data)
                .build();
    }

    public static <T> AgentResponse<T> error(
            AgentTaskType taskType,
            AgentTaskType resolvedRoute,
            long latencyMs,
            String message
    ) {
        return AgentResponse.<T>builder()
                .requestId(getCurrentTraceId())
                .taskType(taskType)
                .resolvedRoute(resolvedRoute)
                .status(ResponseStatus.ERROR)
                .message(message)
                .latencyMs(latencyMs)
                .timestamp(Instant.now())
                .build();
    }
}
