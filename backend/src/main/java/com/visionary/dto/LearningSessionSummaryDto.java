package com.visionary.dto;

import com.visionary.entity.LearningSession;

import java.time.LocalDateTime;

public record LearningSessionSummaryDto(
        Long id,
        Long userId,
        String topic,
        LearningSession.SessionStatus status,
        LearningSession.LearningPhase currentPhase,
        String lastMessagePreview,
        int messageCount,
        LocalDateTime gmtCreated,
        LocalDateTime gmtModified
) {
}
