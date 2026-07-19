package com.visionary.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;

/**
 * MDC Trace ID 拦截器 - 在每个请求到达 Controller 前生成或获取 Trace ID。
 * <p>Trace ID 用于全链路日志追踪，确保一个请求的完整生命周期可被追踪。</p>
 */
@Slf4j
@Component
public class TraceIdInterceptor implements HandlerInterceptor {

    private static final String TRACE_ID_KEY = "traceId";
    private static final String HEADER_TRACE_ID = "X-Trace-Id";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 优先从请求头获取（用于服务间调用链路传递）
        String traceId = request.getHeader(HEADER_TRACE_ID);

        // 如果请求头没有，生成新的 UUID
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }

        // 放入 MDC，供日志和 AgentResponse 使用
        MDC.put(TRACE_ID_KEY, traceId);

        // 设置响应头，方便客户端追踪
        response.setHeader(HEADER_TRACE_ID, traceId);

        log.debug("Trace ID initialized: {}", traceId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // 请求结束后清理 MDC，防止线程池复用导致污染
        MDC.remove(TRACE_ID_KEY);
    }
}
