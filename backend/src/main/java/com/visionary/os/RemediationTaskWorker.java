package com.visionary.os;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.config.LearningOsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "visionary.learning-os.async-remediation", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class RemediationTaskWorker {

    private final RemedialGenerationService remedialGenerationService;
    private final LearningOsProperties learningOsProperties;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RemediationProgressStore progressStore;

    @Scheduled(fixedDelay = 3000)
    public void pollRemediationQueue() {
        for (int i = 0; i < 3; i++) {
            String json = redisTemplate.opsForList().rightPop(learningOsProperties.getRemediationQueueKey());
            if (json == null || json.isBlank()) {
                return;
            }
            try {
                RemediationJob job = objectMapper.readValue(json, RemediationJob.class);
                progressStore.running(job.runId(), "LearningOS", "异步 Worker 开始处理补救任务", 10);
                int generated = remedialGenerationService.generateRemedialPack(
                        job.runId(),
                        job.learningSessionId(),
                        job.userId(),
                        job.profileSnapshot(),
                        job.accuracy(),
                        job.weakPoints(),
                        job.errorPatterns(),
                        job.feedback()
                );
                log.info("[RemediationTaskWorker] completed runId={} generated={} source={}",
                        job.runId(), generated, job.source());
            } catch (Exception e) {
                log.error("[RemediationTaskWorker] failed to process job: {}", e.getMessage());
                try {
                    RemediationJob job = objectMapper.readValue(json, RemediationJob.class);
                    progressStore.failed(job.runId(), e.getMessage());
                } catch (Exception ignored) {
                    // ignore secondary parse failure
                }
            }
        }
    }
}
