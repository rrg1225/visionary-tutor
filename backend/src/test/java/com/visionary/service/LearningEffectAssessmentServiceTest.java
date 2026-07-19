package com.visionary.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.client.DeepSeekApiClient;
import com.visionary.entity.LearningEventMetric;
import com.visionary.entity.User;
import com.visionary.repository.LearningEventMetricRepository;
import com.visionary.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LearningEffectAssessmentServiceTest {

    @Mock LearningEventMetricRepository metricsRepository;
    @Mock UserRepository userRepository;
    @Mock DeepSeekApiClient deepSeekApiClient;
    @Mock ReplanTriggerService replanTriggerService;
    @Mock KnowledgeTracingService knowledgeTracingService;

    LearningEffectAssessmentService service;

    @BeforeEach
    void setUp() {
        service = new LearningEffectAssessmentService(
                metricsRepository,
                userRepository,
                deepSeekApiClient,
                new ObjectMapper(),
                replanTriggerService,
                knowledgeTracingService
        );
    }

    @Test
    void recordsMetricsAndDelegatesQuizTracing() {
        service.recordMetric(1L, 2L, "QUIZ_ACCURACY", "padding", 0.7, "7/10", "quiz");
        service.recordMetric(1L, 2L, "FEEDBACK", "padding", null, "helpful", "resource");
        service.recordMasteryDelta(1L, 2L, "padding", 40, 75, "post-test");

        ArgumentCaptor<LearningEventMetric> captor = ArgumentCaptor.forClass(LearningEventMetric.class);
        verify(metricsRepository, times(3)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(LearningEventMetric::getMetricType)
                .containsExactly("QUIZ_ACCURACY", "FEEDBACK", "MASTERY_DELTA");
        assertThat(captor.getAllValues().get(2).getValueNumeric()).isEqualTo(35);
        verify(knowledgeTracingService).recordPracticeAccuracy(1L, "padding", 0.7);
        verifyNoMoreInteractions(knowledgeTracingService);
    }

    @Test
    void insufficientEvidenceReturnsEmptyRadarWithoutCallingModel() {
        when(userRepository.findById(4L)).thenReturn(Optional.empty());
        when(metricsRepository.findByUserIdOrderByEventTimeDesc(4L)).thenReturn(List.of(
                metric("REPORT_VIEW", "general", null, null, null, 1)
        ));

        LearningEffectAssessmentService.AssessmentResult result = service.assessAndRecommend(4L, 9L);

        assertThat(result.detail().insufficientData()).isTrue();
        assertThat(result.detail().radarData()).isEmpty();
        assertThat(result.detail().meaningfulMetricCount()).isZero();
        assertThat(result.llmUsed()).isFalse();
        verifyNoInteractions(deepSeekApiClient, replanTriggerService);
    }

    @Test
    void deterministicAssessmentBuildsRadarCurvePrePostAndTriggersReplan() {
        User user = new User();
        user.setLearnerProfileSnapshot("{\"weakPoints\":[\"stride\"]}");
        when(userRepository.findById(5L)).thenReturn(Optional.of(user));
        when(deepSeekApiClient.isConfigured()).thenReturn(false);
        when(metricsRepository.findByUserIdOrderByEventTimeDesc(5L)).thenReturn(List.of(
                metric("POST_TEST", "padding", 0.75, null, null, 7),
                metric("PRE_TEST", "padding", 0.35, null, null, 1),
                metric("MASTERY_DELTA", "padding", 0.4, 40.0, 75.0, 6),
                metric("QUIZ_ACCURACY", "padding", 0.45, null, null, 5),
                metric("FEEDBACK", "padding", null, null, null, 4),
                metric("PATH_STEP_COMPLETED", "padding", 1.0, null, null, 3)
        ));

        LearningEffectAssessmentService.AssessmentResult result = service.assessAndRecommend(5L, 12L);

        assertThat(result.llmUsed()).isFalse();
        assertThat(result.detail().radarData()).hasSize(5);
        assertThat(result.detail().masteryCurve()).hasSize(5);
        assertThat(result.detail().prePostSummary().comparable()).isTrue();
        assertThat(result.detail().prePostSummary().delta()).isEqualTo(40.0);
        assertThat(result.detail().shouldReplan()).isTrue();
        verify(replanTriggerService).triggerAfterQuiz(eq(5L), eq(12L), eq(0.45), anyList(), anyList(), anyString());
    }

    @Test
    void modelNarrativeIsParsedButCannotRemoveDeterministicReplan() throws Exception {
        when(userRepository.findById(6L)).thenReturn(Optional.empty());
        when(metricsRepository.findByUserIdOrderByEventTimeDesc(6L)).thenReturn(List.of(
                metric("QUIZ_ACCURACY", "stride", 0.9, null, null, 1),
                metric("QUIZ_ACCURACY", "stride", 0.8, null, null, 2),
                metric("MASTERY_DELTA", "stride", 0.2, 60.0, 80.0, 3)
        ));
        when(deepSeekApiClient.isConfigured()).thenReturn(true);
        when(deepSeekApiClient.chat(anyString(), anyString(), eq(false))).thenReturn("""
                prefix {"summary":"steady improvement","suggestions":["continue practice"],
                "shouldReplan":false,"replanReason":""} suffix
                """);

        LearningEffectAssessmentService.AssessmentResult result = service.assessAndRecommend(6L, null);

        assertThat(result.llmUsed()).isTrue();
        assertThat(result.summary()).isEqualTo("steady improvement");
        assertThat(result.detail().suggestions()).containsExactly("continue practice");
        assertThat(result.detail().shouldReplan()).isFalse();
        verifyNoInteractions(replanTriggerService);
    }

    @Test
    void malformedModelNarrativeFallsBackToDeterministicAdvice() throws Exception {
        when(userRepository.findById(7L)).thenReturn(Optional.empty());
        when(metricsRepository.findByUserIdOrderByEventTimeDesc(7L)).thenReturn(List.of(
                metric("QUIZ_ACCURACY", null, 0.7, null, null, 1),
                metric("FEEDBACK", null, null, null, null, 2)
        ));
        when(deepSeekApiClient.isConfigured()).thenReturn(true);
        when(deepSeekApiClient.chat(anyString(), anyString(), eq(false))).thenThrow(new IllegalStateException("offline"));

        LearningEffectAssessmentService.AssessmentResult result = service.assessAndRecommend(7L, null);

        assertThat(result.llmUsed()).isFalse();
        assertThat(result.summary()).isNotBlank();
        assertThat(result.detail().suggestions()).isNotEmpty();
    }

    private static LearningEventMetric metric(String type, String concept, Double value,
                                              Double before, Double after, long minute) {
        return LearningEventMetric.builder()
                .userId(1L).learningSessionId(1L).metricType(type).concept(concept)
                .valueNumeric(value).beforeValue(before).afterValue(after)
                .eventTime(Instant.parse("2026-07-01T00:00:00Z").plusSeconds(minute * 60))
                .source("test").build();
    }
}
