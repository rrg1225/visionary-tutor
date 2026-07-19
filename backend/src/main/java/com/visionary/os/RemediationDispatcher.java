package com.visionary.os;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.config.LearningOsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RemediationDispatcher {

    private final RemedialGenerationService remedialGenerationService;
    private final LearningOsProperties learningOsProperties;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RemediationProgressStore progressStore;

    /**
     * @return true if job was queued for async worker
     */
    public boolean dispatch(RemediationJob job) {
        progressStore.queued(job.runId());
        if (shouldUseAsyncQueue()) {
            try {
                redisTemplate.opsForList().leftPush(
                        learningOsProperties.getRemediationQueueKey(),
                        objectMapper.writeValueAsString(job)
                );
                log.info("[RemediationDispatcher] queued async job runId={} source={}", job.runId(), job.source());
                return true;
            } catch (Exception e) {
                log.warn("[RemediationDispatcher] queue failed, falling back to sync: {}", e.getMessage());
            }
        }
        runSync(job);
        return false;
    }

    private void runSync(RemediationJob job) {
        try {
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
            log.info("[RemediationDispatcher] sync completed runId={} generated={}", job.runId(), generated);
        } catch (Exception e) {
            progressStore.failed(job.runId(), e.getMessage());
            throw e;
        }
    }

    private boolean shouldUseAsyncQueue() {
        if (!learningOsProperties.isAsyncRemediation()) {
            return false;
        }
        try {
            redisTemplate.getConnectionFactory().getConnection().ping();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
