package com.visionary.knowledge;

import java.util.List;

public record SystemKnowledgeCatalog(
        String version,
        String reviewStatus,
        List<Item> items
) {
    public record Item(
            String slug,
            String kind,
            String title,
            String subtitle,
            String description,
            String subject,
            String difficulty,
            String audience,
            int estimatedMinutes,
            String authorLabel,
            Integer publicationYear,
            String venue,
            String contentResource,
            String licenseName,
            List<String> learningObjectives,
            List<String> prerequisites,
            List<Section> sections,
            List<Source> sources
    ) {
    }

    public record Section(
            String id,
            int order,
            String title,
            String summary,
            List<String> reflectionQuestions,
            List<String> commonErrors,
            String relatedLab
    ) {
    }

    public record Source(
            String title,
            String url,
            String sourceType,
            String licenseName
    ) {
    }
}
