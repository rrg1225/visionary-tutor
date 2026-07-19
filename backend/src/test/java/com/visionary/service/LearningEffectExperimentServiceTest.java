package com.visionary.service;

import com.visionary.entity.LearningEventMetric;
import com.visionary.entity.ResourceUsageRecord;
import com.visionary.repository.LearningEventMetricRepository;
import com.visionary.repository.ResourceUsageRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LearningEffectExperimentServiceTest {

    @Mock LearningEventMetricRepository metricRepository;
    @Mock ResourceUsageRecordRepository usageRepository;
    @Mock LearningEffectAssessmentService assessmentService;
    @Mock KnowledgeTracingService knowledgeTracingService;

    LearningEffectExperimentService service;

    @BeforeEach
    void setUp() {
        service = new LearningEffectExperimentService(
                metricRepository, usageRepository, assessmentService, knowledgeTracingService);
    }

    @Test
    void postTestRecordsMetricTracingAndMasteryDelta() {
        LearningEventMetric pre = metric(5L, 11L, "PRE_TEST", "padding", 0.4, null, null, 1);
        when(metricRepository.findFirstByUserIdAndLearningSessionIdAndMetricTypeAndConceptOrderByEventTimeDesc(
                5L, 11L, "PRE_TEST", "padding")).thenReturn(Optional.of(pre));

        service.recordPostTest(5L, 11L, " padding ", 82);

        verify(assessmentService).recordMetric(5L, 11L, "POST_TEST", "padding", 0.82,
                "explicit_post_test", "learning_effect_experiment");
        verify(knowledgeTracingService).recordPracticeAccuracy(5L, "padding", 0.82);
        verify(assessmentService).recordMasteryDelta(5L, 11L, "padding", 40, 82,
                "learning_effect_experiment");
    }

    @Test
    void postTestIgnoresMissingUserAndClampsScores() {
        service.recordPostTest(null, 1L, "padding", 80);
        verifyNoInteractions(metricRepository, assessmentService, knowledgeTracingService);

        when(metricRepository.findFirstByUserIdAndLearningSessionIdAndMetricTypeAndConceptOrderByEventTimeDesc(
                1L, null, "PRE_TEST", "general")).thenReturn(Optional.empty());
        service.recordPostTest(1L, null, " ", -150);
        verify(assessmentService).recordMetric(1L, null, "POST_TEST", "general", 0.0,
                "explicit_post_test", "learning_effect_experiment");
    }

    @Test
    void reportCombinesScoresUsageAndChronologicalEvidence() {
        List<LearningEventMetric> metrics = List.of(
                metric(2L, 8L, "POST_TEST", "padding", 0.8, null, null, 4),
                metric(2L, 8L, "PRE_TEST", "padding", 40.0, null, null, 1),
                metric(2L, 8L, "MASTERY_DELTA", "stride", 0.3, 0.3, 0.7, 3),
                metric(99L, 8L, "POST_TEST", "foreign", 1.0, null, null, 2)
        );
        List<ResourceUsageRecord> usage = List.of(
                usage(10L, "view", 120, null, 1),
                usage(10L, "complete", 300, "fixed wrong formula", 4),
                usage(11L, null, null, "", 7)
        );
        when(metricRepository.findByLearningSessionIdOrderByEventTimeDesc(8L)).thenReturn(metrics);
        when(usageRepository.findByUserIdAndLearningSessionIdOrderByGmtCreatedDesc(2L, 8L)).thenReturn(usage);
        when(knowledgeTracingService.confidenceForConcept(2L, "padding")).thenReturn(0.85);
        when(knowledgeTracingService.confidenceForConcept(2L, "stride")).thenReturn(0.70);

        LearningEffectExperimentService.LearningEffectExperimentReport report = service.buildReport(2L, 8L);

        assertThat(report.concepts()).hasSize(2);
        assertThat(report.overall().preTestAverage()).isEqualTo(35.0);
        assertThat(report.overall().postTestAverage()).isEqualTo(75.0);
        assertThat(report.overall().delta()).isEqualTo(40.0);
        assertThat(report.behavior().usageEvents()).isEqualTo(3);
        assertThat(report.behavior().totalDurationSeconds()).isEqualTo(420);
        assertThat(report.behavior().completionRatePercent()).isEqualTo(100.0);
        assertThat(report.behavior().errorPatternCount()).isEqualTo(1);
        assertThat(report.behavior().averageRevisitIntervalHours()).isEqualTo(3.0);
        assertThat(report.markdown()).contains("Learning Effect Experiment", "padding", "Evidence Log");
        assertThat(report.evidenceLog()).allMatch(item -> !item.contains("foreign"));
    }

    @Test
    void userWideEmptyReportIsHonestAndExportable() {
        when(metricRepository.findByUserIdOrderByEventTimeDesc(4L)).thenReturn(List.of());
        when(usageRepository.findByUserIdOrderByGmtCreatedDesc(4L)).thenReturn(List.of());

        String markdown = service.exportMarkdown(4L, null);

        assertThat(markdown).contains("preTestAverage: 0.0%", "usageEvents: 0");
    }

    private static LearningEventMetric metric(Long userId, Long sessionId, String type, String concept,
                                              Double value, Double before, Double after, long hour) {
        return LearningEventMetric.builder()
                .userId(userId).learningSessionId(sessionId).metricType(type).concept(concept)
                .valueNumeric(value).beforeValue(before).afterValue(after)
                .eventTime(Instant.parse("2026-07-01T00:00:00Z").plusSeconds(hour * 3600))
                .source("test").build();
    }

    private static ResourceUsageRecord usage(Long resourceId, String action, Integer duration,
                                             String feedback, int hour) {
        ResourceUsageRecord record = new ResourceUsageRecord();
        record.setUserId(2L);
        record.setLearningSessionId(8L);
        record.setResourceId(resourceId);
        record.setActionType(action);
        record.setDurationSeconds(duration);
        record.setFeedback(feedback);
        record.setGmtCreated(LocalDateTime.of(2026, 7, 1, hour, 0));
        return record;
    }
}
