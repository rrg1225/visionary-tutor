package com.visionary.dto;

import java.util.List;

public record ResourceRecommendationResponse(
        List<ResourceRecommendationDto> recommendations,
        List<Long> allArtifactIds
) {
}
