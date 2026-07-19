package com.visionary.agent.worker;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.agent.core.AgentResult;
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
public class AgentHandoffStore {

    private static final Duration TTL = Duration.ofMinutes(45);
    private static final String CONTEXT_PREFIX = "visionary:agent:handoff:context:";
    private static final String RESULT_PREFIX = "visionary:agent:handoff:result:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Map<String, HandoffContext> localContexts = new ConcurrentHashMap<>();
    private final Map<String, AgentResult> localResults = new ConcurrentHashMap<>();

    public void saveContext(HandoffContext context) {
        localContexts.put(context.subTaskId(), context);
        try {
            redisTemplate.opsForValue().set(
                    CONTEXT_PREFIX + context.subTaskId(),
                    objectMapper.writeValueAsString(context),
                    TTL
            );
        } catch (Exception e) {
            log.debug("[AgentHandoffStore] redis context save skipped: {}", e.getMessage());
        }
    }

    public Optional<HandoffContext> getContext(String subTaskId) {
        try {
            String json = redisTemplate.opsForValue().get(CONTEXT_PREFIX + subTaskId);
            if (json != null && !json.isBlank()) {
                return Optional.of(objectMapper.readValue(json, HandoffContext.class));
            }
        } catch (Exception e) {
            log.debug("[AgentHandoffStore] redis context read failed: {}", e.getMessage());
        }
        return Optional.ofNullable(localContexts.get(subTaskId));
    }

    public void saveResult(String subTaskId, AgentResult result) {
        localResults.put(subTaskId, result);
        try {
            redisTemplate.opsForValue().set(
                    RESULT_PREFIX + subTaskId,
                    objectMapper.writeValueAsString(toResultMap(result)),
                    TTL
            );
        } catch (Exception e) {
            log.debug("[AgentHandoffStore] redis result save skipped: {}", e.getMessage());
        }
    }

    public Optional<AgentResult> getResult(String subTaskId) {
        try {
            String json = redisTemplate.opsForValue().get(RESULT_PREFIX + subTaskId);
            if (json != null && !json.isBlank()) {
                Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {});
                return Optional.of(fromResultMap(map));
            }
        } catch (Exception e) {
            log.debug("[AgentHandoffStore] redis result read failed: {}", e.getMessage());
        }
        return Optional.ofNullable(localResults.get(subTaskId));
    }

    public void clearResult(String subTaskId) {
        localResults.remove(subTaskId);
        try {
            redisTemplate.delete(RESULT_PREFIX + subTaskId);
        } catch (Exception ignored) {
            // optional cleanup
        }
    }

    private Map<String, Object> toResultMap(AgentResult result) {
        return Map.of(
                "success", result.success(),
                "output", result.output() != null ? result.output() : "",
                "metadata", result.metadata() != null ? result.metadata() : Map.of()
        );
    }

    @SuppressWarnings("unchecked")
    private AgentResult fromResultMap(Map<String, Object> map) {
        return new AgentResult(
                Boolean.TRUE.equals(map.get("success")),
                String.valueOf(map.getOrDefault("output", "")),
                java.util.List.of(),
                map.get("metadata") instanceof Map<?, ?> metadata
                        ? (Map<String, Object>) metadata
                        : Map.of(),
                java.util.List.of()
        );
    }

    public record HandoffContext(
            String runId,
            String subTaskId,
            String taskType,
            String targetRole,
            Map<String, Object> input,
            String currentTopic,
            String learnerProfileSnapshot
    ) {
    }
}
