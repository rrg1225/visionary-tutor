package com.visionary.dto;

import java.time.LocalDateTime;

public record LearningPathStepDto(
        Long id,
        Integer stepOrder,
        String stepTitle,
        String stepGoal,
        Integer estimatedMinutes,
        String status,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        Integer timeSpentSeconds,
        Long pathNodeId,
        Long artifactId
) {
}
