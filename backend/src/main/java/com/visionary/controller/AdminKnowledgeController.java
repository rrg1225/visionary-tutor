package com.visionary.controller;

import com.visionary.config.VectorDbConfig;
import com.visionary.agent.AgentTaskType;
import com.visionary.rag.ImageCaptionIngestionService;
import com.visionary.rag.KnowledgeIngestionService;
import com.visionary.rag.RagGroundingScorer;
import com.visionary.rag.RagRetrievalResult;
import com.visionary.rag.RagRetrievalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 管理员知识库管理接口。
 * 提供手动触发全量摄取的端点（生产环境建议通过定时任务或 CI 触发）。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/knowledge")
@RequiredArgsConstructor
public class AdminKnowledgeController {

    private static final long RAG_DIAGNOSTIC_TIMEOUT_MS = 8_000L;
    private static final long RAG_DIAGNOSTIC_CACHE_TTL_MS = 2 * 60 * 1000L;
    private static final ConcurrentHashMap<String, CachedDiagnostic> RAG_DIAGNOSTIC_CACHE = new ConcurrentHashMap<>();

    private final VectorDbConfig vectorDbConfig;
    private final KnowledgeIngestionService knowledgeIngestionService;
    private final ImageCaptionIngestionService imageCaptionIngestionService;
    private final RagRetrievalService ragRetrievalService;
    private final RagGroundingScorer groundingScorer;

    private record CachedDiagnostic(Map<String, Object> payload, long timestamp) {
        boolean fresh() {
            return System.currentTimeMillis() - timestamp <= RAG_DIAGNOSTIC_CACHE_TTL_MS;
        }
    }

    @GetMapping("/rag-diagnostic")
    public ResponseEntity<Map<String, Object>> ragDiagnostic(@RequestParam String query) {
        long start = System.currentTimeMillis();
        String normalizedQuery = query == null ? "" : query.trim();
        if (normalizedQuery.isBlank()) {
            return ResponseEntity.ok(Map.of(
                    "query", "",
                    "status", "EMPTY_QUERY",
                    "grounded", false,
                    "durationMs", System.currentTimeMillis() - start
            ));
        }

        CachedDiagnostic cached = RAG_DIAGNOSTIC_CACHE.get(normalizedQuery.toLowerCase());
        if (cached != null && cached.fresh()) {
            Map<String, Object> payload = new LinkedHashMap<>(cached.payload());
            payload.put("cacheHit", true);
            payload.put("durationMs", System.currentTimeMillis() - start);
            return ResponseEntity.ok(payload);
        }

        CompletableFuture<Map<String, Object>> diagnostic = CompletableFuture.supplyAsync(
                () -> buildRagDiagnostic(normalizedQuery, start)
        );
        try {
            Map<String, Object> payload = diagnostic.get(RAG_DIAGNOSTIC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            RAG_DIAGNOSTIC_CACHE.put(normalizedQuery.toLowerCase(), new CachedDiagnostic(payload, System.currentTimeMillis()));
            return ResponseEntity.ok(payload);
        } catch (TimeoutException e) {
            diagnostic.cancel(true);
            log.warn("RAG diagnostic timed out after {}ms: query={}", RAG_DIAGNOSTIC_TIMEOUT_MS, normalizedQuery);
            return ResponseEntity.ok(Map.of(
                    "query", normalizedQuery,
                    "status", "TIMEOUT_FALLBACK",
                    "grounded", false,
                    "citations", java.util.List.of(),
                    "contextPreview", "RAG 诊断超时，演示时请检查 Chroma、Embedding/Rerank 配置或稍后重试。",
                    "faithfulnessScore", 0.0,
                    "durationMs", System.currentTimeMillis() - start
            ));
        } catch (Exception e) {
            log.warn("RAG diagnostic failed: query={}, error={}", normalizedQuery, e.getMessage());
            return ResponseEntity.ok(Map.of(
                    "query", normalizedQuery,
                    "status", "FAILED_FALLBACK",
                    "grounded", false,
                    "citations", java.util.List.of(),
                    "contextPreview", "RAG 诊断失败：" + e.getMessage(),
                    "faithfulnessScore", 0.0,
                    "durationMs", System.currentTimeMillis() - start
            ));
        }
    }

    private Map<String, Object> buildRagDiagnostic(String query, long start) {
        RagRetrievalResult result = ragRetrievalService.retrieveForTask(AgentTaskType.RESOURCE_GENERATION, query);
        String contextPreview = result.groundedContextBlock();
        double faithfulness = groundingScorer.citationAnchorCoverage(contextPreview, result);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", query);
        payload.put("status", result.hasGroundedEvidence() ? "OK" : "NO_EVIDENCE");
        payload.put("grounded", result.hasGroundedEvidence());
        payload.put("citations", result.citations());
        payload.put("contextPreview", contextPreview);
        payload.put("faithfulnessScore", Math.round(faithfulness * 1000D) / 1000D);
        payload.put("durationMs", System.currentTimeMillis() - start);
        payload.put("cacheHit", false);
        return payload;
    }

    @PostMapping("/ingest")
    public ResponseEntity<Map<String, Object>> triggerIngestion() {
        log.info("收到知识库全量摄取请求...");
        long start = System.currentTimeMillis();
        int count = knowledgeIngestionService.ingestKnowledgeBase();
        long duration = System.currentTimeMillis() - start;

        Map<String, Object> result = Map.of(
                "status", "success",
                "segmentsIngested", count,
                "durationMs", duration,
                "chromaBaseUrl", vectorDbConfig.getChromaBaseUrl(),
                "collection", vectorDbConfig.getCollectionName()
        );
        return ResponseEntity.ok(result);
    }

    @PostMapping("/ingest/images")
    public ResponseEntity<Map<String, Object>> triggerImageIngestion() {
        log.info("收到图像字幕摄取请求，启动后台任务...");
        new Thread(() -> {
            try {
                imageCaptionIngestionService.ingestAllImages();
            } catch (Exception e) {
                log.error("后台图像摄取任务异常", e);
            }
        }).start();
        return ResponseEntity.ok(Map.of(
                "status", "started",
                "message", "Image caption ingestion job has been started in background."
        ));
    }
}
