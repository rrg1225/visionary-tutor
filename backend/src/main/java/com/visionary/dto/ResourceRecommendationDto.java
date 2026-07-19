package com.visionary.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ResourceRecommendationDto(
        Long artifactId,
        String artifactType,
        String title,
        String recommendationReason,
        int score
) {

    /** Backward-compatible alias for frontend consumers expecting {@code reason}. */
    @JsonProperty("reason")
    public String reason() {
        return recommendationReason;
    }
}
