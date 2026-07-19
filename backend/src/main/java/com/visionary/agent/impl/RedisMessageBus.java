package com.visionary.agent.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.agent.core.AgentMessage;
import com.visionary.agent.core.MessageBus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Redis-backed persistent MessageBus using Redis Lists (LPUSH / BRPOP pattern).
 * Suitable for real async handoff between Supervisor and Specialists.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisMessageBus implements MessageBus {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String QUEUE_PREFIX = "visionary:agent:queue:";

    @Override
    public void publish(AgentMessage message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            String queueKey = QUEUE_PREFIX + message.toRole();
            redisTemplate.opsForList().leftPush(queueKey, json);
            log.debug("[RedisMessageBus] Published {} -> {} (queue: {})", message.fromRole(), message.toRole(), queueKey);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize AgentMessage: {}", e.getMessage());
        }
    }

    @Override
    public List<AgentMessage> poll(String agentRole, int max) {
        String queueKey = QUEUE_PREFIX + agentRole;
        List<AgentMessage> result = new ArrayList<>();
        for (int i = 0; i < max; i++) {
            String json = redisTemplate.opsForList().rightPop(queueKey);
            if (json == null) break;
            try {
                AgentMessage msg = objectMapper.readValue(json, AgentMessage.class);
                result.add(msg);
            } catch (Exception e) {
                log.warn("Failed to deserialize message from Redis: {}", e.getMessage());
            }
        }
        return result;
    }

    @Override
    public void ack(String messageId) {
        // For list-based queue, ack is not strictly needed.
        // In production you would move the message to a "processing" or "dead-letter" list.
        log.debug("[RedisMessageBus] ack {}", messageId);
    }

    /**
     * Helper to create a simple task message.
     */
    public static AgentMessage createTaskMessage(String from, String to, String runId, String taskId, String type) {
        return new AgentMessage(
                UUID.randomUUID().toString(),
                from,
                to,
                type,
                runId,
                java.util.Map.of("taskId", taskId),
                Instant.now()
        );
    }
}