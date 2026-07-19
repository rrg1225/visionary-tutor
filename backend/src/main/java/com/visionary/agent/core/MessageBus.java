package com.visionary.agent.core;

import java.util.List;

/**
 * Message bus for explicit Agent-to-Agent handoff.
 * Initial implementation can be in-memory; later Redis Stream or persistent queue.
 */
public interface MessageBus {

    void publish(AgentMessage message);

    List<AgentMessage> poll(String agentRole, int max);

    void ack(String messageId);
}