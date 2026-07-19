package com.visionary.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record UserMemoryDto(
        Long id,
        String memoryType,
        String memoryKey,
        String memoryValue,
        String sourceType,
        Integer priority,
        BigDecimal confidenceScore,
        String reviewStatus,
        Boolean isActive,
        LocalDateTime updatedAt
) {
}
