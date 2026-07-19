package com.visionary.config;

import com.visionary.agent.core.MessageBus;
import com.visionary.agent.impl.InMemoryMessageBus;
import com.visionary.agent.impl.RedisStreamMessageBus;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@RequiredArgsConstructor
public class MessageBusConfig {

    private final AgentOrchestrationProperties orchestrationProperties;

    @Bean
    @Primary
    public MessageBus messageBus(
            InMemoryMessageBus inMemoryMessageBus,
            RedisStreamMessageBus redisStreamMessageBus
    ) {
        if (orchestrationProperties.isDistributed()) {
            return redisStreamMessageBus;
        }
        return inMemoryMessageBus;
    }
}
