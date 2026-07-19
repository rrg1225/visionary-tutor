package com.visionary.exam;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FixedExamCatalogServiceTest {

    private FixedExamCatalogService service;

    @BeforeEach
    void setUp() {
        service = new FixedExamCatalogService(new ObjectMapper());
        service.initialize();
    }

    @Test
    void loadsFiveVersionedPapersWithEightQuestionsAndOneHundredPointsEach() {
        assertEquals("1.0.0", service.version());
        assertEquals(5, service.listPapers().size());
        service.listPapers().forEach(summary -> {
            assertEquals(8, summary.questionCount());
            assertEquals(0, new BigDecimal("100").compareTo(summary.maxScore()));
            assertNotNull(summary.reviewStatus());
        });
    }

    @Test
    void learnerPaperViewDoesNotExposeAnswersOrScoringPoints() throws Exception {
        var paper = service.paperView("cnn-convolution-v1");
        String json = new ObjectMapper().writeValueAsString(paper);
        assertEquals(8, paper.questions().size());
        assertFalse(json.contains("standardAnswer"));
        assertFalse(json.contains("scoringPoints"));
        assertFalse(json.contains("explanation"));
    }

    @Test
    void rejectsCatalogsThatDoNotMeetFixedPaperCount() {
        FixedExamCatalog invalid = new FixedExamCatalog("1", "REVIEW", java.util.List.of());
        assertThrows(IllegalStateException.class, () -> service.validateCatalog(invalid));
    }
}
