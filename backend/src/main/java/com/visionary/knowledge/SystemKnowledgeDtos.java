package com.visionary.knowledge;

import java.util.List;

public final class SystemKnowledgeDtos {

    private SystemKnowledgeDtos() {
    }

    public record ContentSummary(
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
            String catalogVersion,
            String reviewStatus,
            String licenseName,
            int sectionCount,
            int contentLength,
            List<String> learningObjectives,
            List<SystemKnowledgeCatalog.Source> sources
    ) {
    }

    public record ContentView(
            ContentSummary summary,
            List<String> prerequisites,
            List<SystemKnowledgeCatalog.Section> sections,
            String contentMarkdown
    ) {
    }
}
