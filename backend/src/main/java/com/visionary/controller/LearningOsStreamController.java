package com.visionary.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.os.RemediationProgress;
import com.visionary.os.RemediationProgressStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.Executor;

@Slf4j
@RestController
@RequestMapping("/api/learning-os")
public class LearningOsStreamController {

    private static final long SSE_TIMEOUT_MS = 180_000L;

    private final RemediationProgressStore progressStore;
    private final ObjectMapper objectMapper;
    private final Executor sseStreamExecutor;

    public LearningOsStreamController(
            RemediationProgressStore progressStore,
            ObjectMapper objectMapper,
            @Qualifier("sseStreamExecutor") Executor sseStreamExecutor
    ) {
        this.progressStore = progressStore;
        this.objectMapper = objectMapper;
        this.sseStreamExecutor = sseStreamExecutor;
    }

    @GetMapping("/remediation/progress")
    public RemediationProgress progress(@RequestParam String runId) {
        return progressStore.get(runId).orElse(RemediationProgress.failed(runId, "未找到该补救任务进度"));
    }

    @GetMapping(value = "/remediation/progress/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamProgress(@RequestParam String runId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        sseStreamExecutor.execute(() -> pollProgress(emitter, runId));
        return emitter;
    }

    private void pollProgress(SseEmitter emitter, String runId) {
        try {
            String lastPayload = "";
            for (int i = 0; i < 120; i++) {
                RemediationProgress progress = progressStore.get(runId)
                        .orElse(RemediationProgress.queued(runId));
                String payload = objectMapper.writeValueAsString(progress);
                if (!payload.equals(lastPayload)) {
                    emitter.send(SseEmitter.event().name("progress").data(payload));
                    lastPayload = payload;
                }
                if (progress.terminal()) {
                    emitter.send(SseEmitter.event().name("complete").data(payload));
                    emitter.complete();
                    return;
                }
                Thread.sleep(800);
            }
            emitter.send(SseEmitter.event().name("timeout").data("{\"message\":\"progress polling timeout\"}"));
            emitter.complete();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitter.completeWithError(e);
        } catch (IOException e) {
            emitter.completeWithError(e);
        } catch (Exception e) {
            log.warn("[LearningOS SSE] stream failed: {}", e.getMessage());
            emitter.completeWithError(e);
        }
    }
}
