package com.visionary.dto;

import java.time.LocalDateTime;

public record SessionChatMessageDto(
        Long id,
        Long learningSessionId,
        String role,
        String contextType,
        String contextKey,
        String contextTitle,
        String content,
        String metadataJson,
        int seq,
        LocalDateTime gmtCreated
) {
}
