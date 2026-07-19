package com.visionary.dto;

import java.time.LocalDateTime;

public record NotificationDto(
        Long id,
        String type,
        String payloadJson,
        boolean read,
        LocalDateTime createdAt
) {
}
