package com.visionary.dto;

import java.util.List;

public record RecommendationPushDto(
        Long pushId,
        Long learningSessionId,
        String message,
        String pushSource,
        List<ResourceRecommendationDto> recommendations
) {
}
