package com.visionary.dto;

import com.visionary.entity.SharedTextbook;

import java.time.LocalDateTime;

public record SharedTextbookDto(
        Long id,
        Long ownerUserId,
        String title,
        String description,
        String contentMarkdown,
        String subjectTag,
        String sourceType,
        String sourceTitle,
        String sourceUrl,
        String licenseName,
        String rightsStatement,
        boolean rightsConfirmed,
        String visibility,
        String reviewStatus,
        String aiReviewStatus,
        String aiRiskLevel,
        String aiReviewReason,
        String rejectionReason,
        int viewCount,
        LocalDateTime createdAt
) {
    public static SharedTextbookDto from(SharedTextbook entity, boolean includeContent) {
        return new SharedTextbookDto(
                entity.getId(),
                entity.getOwnerUserId(),
                entity.getTitle(),
                entity.getDescription(),
                includeContent ? entity.getContentMarkdown() : null,
                entity.getSubjectTag(),
                entity.getSourceType(),
                entity.getSourceTitle(),
                entity.getSourceUrl(),
                entity.getLicenseName(),
                entity.getRightsStatement(),
                Boolean.TRUE.equals(entity.getRightsConfirmed()),
                entity.getVisibility(),
                entity.getReviewStatus(),
                entity.getAiReviewStatus(),
                entity.getAiRiskLevel(),
                entity.getAiReviewReason(),
                entity.getRejectionReason(),
                entity.getViewCount() != null ? entity.getViewCount() : 0,
                entity.getGmtCreated()
        );
    }

    public static SharedTextbookDto summary(SharedTextbook entity) {
        return from(entity, false);
    }
}
