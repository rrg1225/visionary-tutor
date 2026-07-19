package com.visionary.dto;

import com.visionary.entity.LearningOsEvent;

public record LearningOsEventDto(
        Long id,
        String eventType,
        String policyReason,
        String createdAt
) {
    public static LearningOsEventDto from(LearningOsEvent event) {
        return new LearningOsEventDto(
                event.getId(),
                event.getEventType(),
                event.getPolicyReason(),
                event.getGmtCreated() != null ? event.getGmtCreated().toString() : null
        );
    }
}
