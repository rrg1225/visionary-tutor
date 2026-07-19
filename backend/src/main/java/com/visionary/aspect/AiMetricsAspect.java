package com.visionary.aspect;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Aspect
@Component
@Slf4j
public class AiMetricsAspect {

    private static final ThreadLocal<AtomicInteger> TOKEN_HOLDER = ThreadLocal.withInitial(() -> new AtomicInteger(-1));

    /**
     * 拦截 HttpAiClientSupport.postJsonWithRetry 以提取 usage.total_tokens（如果存在）。
     */
    @Around("execution(* com.visionary.client.HttpAiClientSupport.postJsonWithRetry(..))")
    public Object captureTokenUsage(ProceedingJoinPoint pjp) throws Throwable {
        Object result = pjp.proceed();
        if (result instanceof String json && json.contains("\"usage\"")) {
            try {
                // 简单字符串解析避免额外依赖，生产可用 ObjectMapper
                int idx = json.indexOf("\"total_tokens\"");
                if (idx > 0) {
                    int start = json.indexOf(":", idx) + 1;
                    int end = json.indexOf(",", start);
                    if (end < 0) end = json.indexOf("}", start);
                    int tokens = Integer.parseInt(json.substring(start, end).trim());
                    TOKEN_HOLDER.get().set(tokens);
                }
            } catch (Exception ignored) {
                // 解析失败不影响主流程
            }
        }
        return result;
    }

    /**
     * 拦截 DeepSeekApiClient 和 QwenVlApiClient 的核心大模型调用方法。
     */
    @Around("execution(* com.visionary.client.DeepSeekApiClient.chat(..)) || " +
            "execution(* com.visionary.client.DeepSeekApiClient.streamChat(..)) || " +
            "execution(* com.visionary.client.DeepSeekApiClient.streamChatMessages(..)) || " +
            "execution(* com.visionary.client.QwenVlApiClient.analyzeImageWithUrl(..)) || " +
            "execution(* com.visionary.client.QwenVlApiClient.analyzeImageWithBase64(..))")
    public Object logAiCallMetrics(ProceedingJoinPoint pjp) throws Throwable {
        String method = pjp.getSignature().toShortString();
        long start = System.currentTimeMillis();
        boolean success = true;
        boolean ragFallback = false; // RAG 降级回退（当前客户端层面 fallbackStream 视为类似策略，实际 RAG 降级在 HierarchicalRagService）
        int tokens = -1;

        try {
            TOKEN_HOLDER.get().set(-1);
            Object result = pjp.proceed();
            tokens = TOKEN_HOLDER.get().get();
            // 对于 streamChat 的 fallback 场景，onErrorResume 会触发 fallbackStream，可在此扩展 ThreadLocal 标记
            if (method.contains("streamChat") && result != null) {
                // 简化处理：若发生过 fallback，实际生产可通过 Flux.doOnNext 标记
            }
            return result;
        } catch (Exception ex) {
            success = false;
            throw ex;
        } finally {
            long latency = System.currentTimeMillis() - start;
            log.info("ai_metrics,method={},latency_ms={},tokens={},success={},rag_fallback={}",
                    method, latency, tokens, success, ragFallback);
            TOKEN_HOLDER.remove();
        }
    }
}
