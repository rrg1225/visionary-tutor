package com.visionary.service;

import com.visionary.dto.KnowledgeTracingRadarDto;
import com.visionary.entity.LearningMetrics;
import com.visionary.repository.LearningMetricsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KnowledgeTracingServiceTest {

    @Mock
    LearningMetricsRepository repository;

    KnowledgeTracingService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeTracingService(repository);
    }

    @Test
    void confidenceIsExplainableClampedAndDecaysWithTime() {
        assertThat(service.calculateConfidence(null)).isZero();
        assertThat(service.calculateConfidence(row("empty", 0, 0, null, null))).isZero();

        LearningMetrics recent = row("padding", 10, 8, LocalDateTime.now(), null);
        LearningMetrics stale = row("padding", 10, 8, LocalDateTime.now().minusDays(30), null);
        LearningMetrics overReported = row("padding", 10, 20, LocalDateTime.now(), null);

        assertThat(service.calculateConfidence(recent)).isCloseTo(0.8, within(0.01));
        assertThat(service.calculateConfidence(stale)).isLessThan(service.calculateConfidence(recent));
        assertThat(service.calculateConfidence(overReported)).isEqualTo(1.0);
    }

    @Test
    void practiceUpsertsAggregateAndNormalizesInput() {
        when(repository.findByUserIdAndKnowledgeConcept(7L, "CNN padding"))
                .thenReturn(Optional.empty());

        service.recordPracticeAccuracy(7L, "  CNN padding  ", 1.5);

        ArgumentCaptor<LearningMetrics> captor = ArgumentCaptor.forClass(LearningMetrics.class);
        verify(repository).save(captor.capture());
        LearningMetrics saved = captor.getValue();
        assertThat(saved.getKnowledgeConcept()).isEqualTo("CNN padding");
        assertThat(saved.getTotalAttempts()).isEqualTo(10);
        assertThat(saved.getCorrectCount()).isEqualTo(10);
        assertThat(saved.getConfidenceScore()).isBetween(0.99, 1.0);

        service.recordPracticeAccuracy(null, "ignored", 0.5);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void quizOutcomeDeduplicatesAndCapsWeakPoints() {
        when(repository.findByUserIdAndKnowledgeConcept(eq(9L), anyString()))
                .thenReturn(Optional.empty());

        service.recordQuizOutcome(9L, 0.4, List.of(
                "padding", "padding", "stride", "", "kernel", "shape", "channel", "receptive field"
        ));

        ArgumentCaptor<LearningMetrics> captor = ArgumentCaptor.forClass(LearningMetrics.class);
        verify(repository, times(6)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(LearningMetrics::getKnowledgeConcept)
                .contains("padding", "stride", "kernel", "shape", "channel")
                .doesNotContain("receptive field");
    }

    @Test
    void radarAndWeaknessQueriesExposeOnlyMeaningfulConcepts() {
        List<LearningMetrics> rows = List.of(
                row("mastered", 10, 9, LocalDateTime.now(), 0.9),
                row("padding", 10, 4, LocalDateTime.now(), 0.4),
                row("stride", 10, 5, LocalDateTime.now(), null),
                row("unused", 0, 0, null, 0.1)
        );
        when(repository.findByUserIdOrderByConfidenceScoreDesc(3L)).thenReturn(rows);

        KnowledgeTracingRadarDto radar = service.getRadarSnapshot(3L);
        assertThat(radar.concepts()).hasSize(3);
        assertThat(radar.insufficientData()).isFalse();
        assertThat(service.buildWeaknessQuery(3L, 2)).isEqualTo("padding stride");
        assertThat(service.weakConceptLabels(3L, 3)).containsExactly("padding", "stride", "unused");

        assertThat(service.getRadarSnapshot(null).concepts()).isEmpty();
        assertThat(service.buildWeaknessQuery(null, 2)).isEmpty();
        assertThat(service.weakConceptLabels(null, 2)).isEmpty();
    }

    @Test
    void confidenceLookupUsesStoredOrCalculatedValues() {
        LearningMetrics calculated = row("padding", 10, 7, LocalDateTime.now(), null);
        when(repository.findByUserIdAndKnowledgeConcept(1L, "padding")).thenReturn(Optional.of(calculated));
        assertThat(service.confidenceForConcept(1L, " padding ")).isCloseTo(0.7, within(0.01));
        assertThat(service.confidenceForConcept(null, "padding")).isZero();
        assertThat(service.confidenceForConcept(1L, " ")).isZero();
    }

    private static LearningMetrics row(String concept, int attempts, int correct,
                                       LocalDateTime practicedAt, Double confidence) {
        LearningMetrics row = new LearningMetrics();
        row.setUserId(1L);
        row.setKnowledgeConcept(concept);
        row.setTotalAttempts(attempts);
        row.setCorrectCount(correct);
        row.setLastPracticedAt(practicedAt);
        row.setConfidenceScore(confidence);
        return row;
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
