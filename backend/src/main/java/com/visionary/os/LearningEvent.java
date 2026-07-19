package com.visionary.os;

import java.time.Instant;
import java.util.Map;

public record LearningEvent(
        LearningEventType type,
        Long userId,
        Long learningSessionId,
        int profileVersion,
        Map<String, Object> payload,
        Instant occurredAt
) {
    public static LearningEvent of(
            LearningEventType type,
            Long userId,
            Long learningSessionId,
            int profileVersion,
            Map<String, Object> payload
    ) {
        return new LearningEvent(type, userId, learningSessionId, profileVersion, payload, Instant.now());
    }
}
