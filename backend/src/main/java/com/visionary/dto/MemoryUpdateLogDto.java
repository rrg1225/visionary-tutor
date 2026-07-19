package com.visionary.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record MemoryUpdateLogDto(
        Long id,
        Long memoryId,
        String oldValue,
        String newValue,
        String updateReason,
        BigDecimal agentScore,
        String updateStatus,
        LocalDateTime createdAt
) {
}
