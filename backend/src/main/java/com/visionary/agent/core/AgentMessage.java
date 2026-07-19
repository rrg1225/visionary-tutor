package com.visionary.agent.core;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AgentMessage(
        @JsonProperty("messageId")
        @JsonAlias("id")
        String messageId,
        AgentMessageType type,
        @JsonProperty("sourceRole")
        @JsonAlias("fromRole")
        String sourceRole,
        @JsonProperty("targetRole")
        @JsonAlias("toRole")
        String targetRole,
        Map<String, Object> payload,
        String taskId,
        Instant timestamp
) {

    public AgentMessage {
        if (messageId == null || messageId.isBlank()) {
            messageId = UUID.randomUUID().toString();
        }
        if (type == null) {
            type = AgentMessageType.HANDOFF;
        }
        if (payload == null) {
            payload = Map.of();
        }
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }

    /**
     * Legacy constructor preserved for existing call sites and Redis payloads.
     */
    public AgentMessage(
            String id,
            String fromRole,
            String toRole,
            String typeString,
            String taskId,
            Map<String, Object> payload,
            Instant timestamp
    ) {
        this(
                id,
                AgentMessageType.fromLegacy(typeString),
                fromRole,
                toRole,
                payload,
                taskId,
                timestamp
        );
    }

    public String id() {
        return messageId;
    }

    public String fromRole() {
        return sourceRole;
    }

    public String toRole() {
        return targetRole;
    }
}
