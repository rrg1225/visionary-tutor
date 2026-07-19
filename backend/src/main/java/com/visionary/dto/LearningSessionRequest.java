package com.visionary.dto;

import com.visionary.entity.LearningSession;

public record LearningSessionRequest(
        Long userId,
        String topic,
        LearningSession.SessionStatus status,
        LearningSession.LearningPhase currentPhase,
        String streamingHandout,
        String conversationSummary,
        String lastEmotionSnapshot,
        String assessmentFileName
) {
}
