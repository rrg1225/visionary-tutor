package com.visionary.os;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class RemediationProgressStore {

    private static final Duration TTL = Duration.ofMinutes(30);
    private static final String KEY_PREFIX = "visionary:learning-os:progress:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Map<String, RemediationProgress> localFallback = new ConcurrentHashMap<>();

    public void save(RemediationProgress progress) {
        localFallback.put(progress.runId(), progress);
        try {
            redisTemplate.opsForValue().set(
                    key(progress.runId()),
                    objectMapper.writeValueAsString(progress),
                    TTL
            );
        } catch (Exception e) {
            log.debug("[RemediationProgress] redis save skipped: {}", e.getMessage());
        }
    }

    public Optional<RemediationProgress> get(String runId) {
        try {
            String json = redisTemplate.opsForValue().get(key(runId));
            if (json != null && !json.isBlank()) {
                return Optional.of(objectMapper.readValue(json, RemediationProgress.class));
            }
        } catch (Exception e) {
            log.debug("[RemediationProgress] redis read failed: {}", e.getMessage());
        }
        return Optional.ofNullable(localFallback.get(runId));
    }

    public void queued(String runId) {
        save(RemediationProgress.queued(runId));
    }

    public void running(String runId, String agentName, String message, int percent) {
        save(RemediationProgress.running(runId, agentName, message, percent));
    }

    public void complete(String runId, int generated) {
        save(RemediationProgress.complete(runId, generated));
    }

    public void failed(String runId, String message) {
        save(RemediationProgress.failed(runId, message));
    }

    private static String key(String runId) {
        return KEY_PREFIX + runId;
    }
}
