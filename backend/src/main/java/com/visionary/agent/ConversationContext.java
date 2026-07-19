package com.visionary.agent;

import com.visionary.dto.ChatMessageDto;

import java.util.List;

/**
 * Assembled LLM context after sliding-window trimming and optional RAG injection.
 */
public record ConversationContext(
        String systemPrompt,
        List<ChatMessageDto> messages,
        MemoryStatus memoryStatus
) {
}
