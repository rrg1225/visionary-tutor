package com.visionary.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.entity.LearningStateReport;
import com.visionary.repository.LearningSessionRepository;
import com.visionary.repository.LearningStateReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LearningStateReportServiceTest {

    @Mock
    private LearningStateReportRepository reportRepository;

    @Mock
    private LearningSessionRepository learningSessionRepository;

    @Mock
    private LearningEvidenceService learningEvidenceService;

    private LearningStateReportService service;

    @BeforeEach
    void setUp() {
        service = new LearningStateReportService(reportRepository, learningSessionRepository, new ObjectMapper(), learningEvidenceService);
    }

    @Test
    void createPersistsSanitizedReportWithMarkers() {
        when(reportRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var view = service.create(7L, new LearningStateReportService.CreateReportRequest(
                null,
                "FIXED_EXAM",
                "cnn-convolution-v1:attempt:12",
                "CNN 综合题卷",
                42,
                600,
                180, // 越界，应被裁剪到 100
                true,
                "本次仅记录到一个可供参考的视觉负荷信号",
                "描述",
                List.of(
                        new LearningStateReportService.MarkerAggregate("cnn-q3", 9, 72),
                        new LearningStateReportService.MarkerAggregate("  ", 3, 50) // 空标记应被过滤
                )
        ));

        assertEquals("FIXED_EXAM", view.contextType());
        assertEquals(100, view.aggregateScore());
        assertTrue(view.sufficient());
        assertEquals(1, view.markers().size());
        assertEquals("cnn-q3", view.markers().get(0).marker());
        assertEquals(72, view.markers().get(0).averageScore());
    }

    @Test
    void createRejectsMissingRequiredFields() {
        assertThrows(ResponseStatusException.class, () ->
                service.create(7L, new LearningStateReportService.CreateReportRequest(
                        null, "FIXED_EXAM", "", null, 0, 0, null, false, "标题", null, null)));
        assertThrows(ResponseStatusException.class, () -> service.create(7L, null));
    }

    @Test
    void markersRoundTripThroughJsonColumn() {
        when(reportRepository.save(any())).thenAnswer(invocation -> {
            LearningStateReport saved = invocation.getArgument(0);
            // 模拟数据库读回：markers_json 原样保留
            assertTrue(saved.getMarkersJson().contains("cnn-q3"));
            return saved;
        });

        var view = service.create(7L, new LearningStateReportService.CreateReportRequest(
                null, "FIXED_EXAM", "paper:attempt:1", "题卷", 10, 60, 50, true,
                "标题", null,
                List.of(new LearningStateReportService.MarkerAggregate("cnn-q3", 5, 66))
        ));

        assertEquals(List.of(new LearningStateReportService.MarkerAggregate("cnn-q3", 5, 66)), view.markers());
    }

    @Test
    void emptyMarkersStoredAsNull() {
        when(reportRepository.save(any())).thenAnswer(invocation -> {
            LearningStateReport saved = invocation.getArgument(0);
            assertNull(saved.getMarkersJson());
            return saved;
        });

        var view = service.create(7L, new LearningStateReportService.CreateReportRequest(
                null, "AI_TUTOR", "main-dialogue", "AI 辅导", 2, 10, null, false,
                "数据不足，未作状态判断", "有效样本不足", List.of()
        ));

        assertEquals(List.of(), view.markers());
        assertNull(view.aggregateScore());
    }
}
