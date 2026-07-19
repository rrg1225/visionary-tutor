package com.visionary.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Multi-turn chat stream request body.
 */
public record StreamChatRequest(
        String systemPrompt,
        @NotEmpty List<@Valid ChatMessageDto> messages,
        String query,
        Boolean enableRag,
        Boolean enableVoice,
        /** Explicit RAG retrieval query; falls back to {@link #query()} or last user message. */
        String ragQuery,
        /** Hint for logging / future routing (e.g. RESOURCE_GENERATION). */
        String taskType,
        Long learningSessionId,
        String studentProfileSnapshot,
        String emotionProfileSnapshot,
        String clientContext,
        /** AUTO, HINT, STEP_BY_STEP or DIRECT_ANSWER. */
        String tutoringMode
) {
    public boolean ragEnabled() {
        return enableRag == null || Boolean.TRUE.equals(enableRag);
    }

    public boolean voiceEnabled() {
        return Boolean.TRUE.equals(enableVoice);
    }
}
