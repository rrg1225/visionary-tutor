package com.visionary.agent.impl;

import com.visionary.agent.core.AgentMessage;
import com.visionary.agent.core.MessageBus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory MessageBus with optional queue polling for distributed handoff tests/dev.
 */
@Slf4j
@Component
public class InMemoryMessageBus implements MessageBus {

    private final Map<String, Deque<AgentMessage>> queues = new ConcurrentHashMap<>();

    @Override
    public void publish(AgentMessage message) {
        queues.computeIfAbsent(message.toRole(), k -> new ArrayDeque<>()).addLast(message);
        log.debug("[MessageBus] {} -> {} : {}", message.fromRole(), message.toRole(), message.id());
    }

    @Override
    public synchronized List<AgentMessage> poll(String agentRole, int max) {
        Deque<AgentMessage> queue = queues.computeIfAbsent(agentRole, k -> new ArrayDeque<>());
        List<AgentMessage> result = new ArrayList<>();
        for (int i = 0; i < max; i++) {
            AgentMessage next = queue.pollFirst();
            if (next == null) {
                break;
            }
            result.add(next);
        }
        return result;
    }

    @Override
    public void ack(String messageId) {
        // In-memory version: messages are not removed for simplicity
        log.debug("[MessageBus] ack {}", messageId);
    }
}