package com.visionary.agent;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Token budget snapshot for frontend memory ring (SSE event: memory_status).
 */
public record MemoryStatus(
        @JsonProperty("currentTokens") int currentTokens,
        @JsonProperty("maxTokens") int maxTokens,
        @JsonProperty("windowMessages") int windowMessages,
        @JsonProperty("droppedMessages") int droppedMessages
) {
}
