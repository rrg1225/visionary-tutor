package com.visionary.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class SystemKnowledgeCatalogServiceTest {

    @Test
    void curatedCatalogHasRequiredMixSourcesAndSubstantialContent() {
        SystemKnowledgeCatalogService service = new SystemKnowledgeCatalogService(new ObjectMapper());

        service.initialize();

        var summaries = service.list();
        assertThat(summaries).hasSize(12);
        Map<String, Long> kinds = summaries.stream()
                .collect(Collectors.groupingBy(SystemKnowledgeDtos.ContentSummary::kind, Collectors.counting()));
        assertThat(kinds).containsEntry("BOOK", 4L)
                .containsEntry("ARTICLE", 3L)
                .containsEntry("PAPER", 3L)
                .containsEntry("REVIEW", 2L);
        assertThat(summaries).allSatisfy(summary -> {
            assertThat(summary.sources()).isNotEmpty();
            assertThat(summary.contentLength()).isGreaterThanOrEqualTo(
                    "BOOK".equals(summary.kind()) ? 5_000 : 1_200
            );
        });
    }
}
