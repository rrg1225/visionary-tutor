package com.visionary.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.entity.LearningStateReport;
import com.visionary.repository.LearningStateReportRepository;
import com.visionary.repository.LearningSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Persists and lists learning-state observation reports (学习状态辅助报告，问题21/23).
 *
 * <p>The browser aggregates camera samples locally and only submits the summary here;
 * insufficient observations are stored as-is with {@code sufficient=false} so the report
 * center can honestly say "数据不足" instead of guessing an emotion.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LearningStateReportService {

    private static final int MAX_DURATION_SECONDS = 24 * 60 * 60;
    private static final int MAX_MARKERS = 50;

    private final LearningStateReportRepository reportRepository;
    private final LearningSessionRepository learningSessionRepository;
    private final ObjectMapper objectMapper;
    private final LearningEvidenceService learningEvidenceService;

    @Transactional
    public ReportView create(Long userId, CreateReportRequest request) {
        if (request == null || isBlank(request.contextType()) || isBlank(request.contextKey())
                || isBlank(request.headline())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "状态报告缺少必要字段");
        }
        Long learningSessionId = validateOwnedSession(userId, request.learningSessionId());

        LearningStateReport report = new LearningStateReport();
        report.setUserId(userId);
        report.setLearningSessionId(learningSessionId);
        report.setContextType(trim(request.contextType(), 64));
        report.setContextKey(trim(request.contextKey(), 191));
        report.setContextTitle(trim(request.contextTitle(), 255));
        report.setSampleCount(clamp(request.sampleCount(), 0, 1_000_000));
        report.setDurationSeconds(clamp(request.durationSeconds(), 0, MAX_DURATION_SECONDS));
        report.setAggregateScore(request.aggregateScore() == null
                ? null
                : clamp(request.aggregateScore(), 0, 100));
        report.setSufficient(Boolean.TRUE.equals(request.sufficient()));
        report.setHeadline(trim(request.headline(), 255));
        report.setDescription(trim(request.description(), 4000));
        report.setMarkersJson(writeMarkers(request.markers()));
        LearningStateReport saved = reportRepository.save(report);
        learningEvidenceService.record(new LearningEvidenceService.Evidence(
                userId, learningSessionId, "LEARNING_STATE", null, null, null, null,
                null, String.valueOf(saved.getId()), request.contextKey(), null, null,
                java.util.Map.of("contextType", saved.getContextType(), "sufficient", saved.isSufficient())
        ));
        return toView(saved);
    }

    @Transactional(readOnly = true)
    public List<ReportView> listMine(Long userId) {
        return reportRepository.findTop100ByUserIdOrderByGmtCreatedDesc(userId).stream()
                .map(this::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ReportView> listByContext(Long userId, String contextType, String contextKey) {
        if (isBlank(contextType) || isBlank(contextKey)) {
            return List.of();
        }
        return reportRepository
                .findByUserIdAndContextTypeAndContextKeyOrderByGmtCreatedDesc(userId, contextType, contextKey)
                .stream()
                .map(this::toView)
                .toList();
    }

    private Long validateOwnedSession(Long userId, Long learningSessionId) {
        if (learningSessionId == null) {
            return null;
        }
        return learningSessionRepository.findById(learningSessionId)
                .filter(session -> userId.equals(session.getUserId()))
                .map(session -> learningSessionId)
                .orElse(null);
    }

    private ReportView toView(LearningStateReport report) {
        return new ReportView(
                report.getId(),
                report.getLearningSessionId(),
                report.getContextType(),
                report.getContextKey(),
                report.getContextTitle(),
                report.getSampleCount(),
                report.getDurationSeconds(),
                report.getAggregateScore(),
                report.isSufficient(),
                report.getHeadline(),
                report.getDescription(),
                readMarkers(report.getMarkersJson()),
                report.getGmtCreated()
        );
    }

    private String writeMarkers(List<MarkerAggregate> markers) {
        if (markers == null || markers.isEmpty()) {
            return null;
        }
        List<MarkerAggregate> sanitized = markers.stream()
                .filter(marker -> marker != null && !isBlank(marker.marker()))
                .limit(MAX_MARKERS)
                .map(marker -> new MarkerAggregate(
                        trim(marker.marker(), 96),
                        clamp(marker.sampleCount(), 0, 1_000_000),
                        clamp(marker.averageScore(), 0, 100)
                ))
                .toList();
        if (sanitized.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(sanitized);
        } catch (Exception exception) {
            log.warn("Cannot serialize state report markers: {}", exception.getMessage());
            return null;
        }
    }

    private List<MarkerAggregate> readMarkers(String markersJson) {
        if (markersJson == null || markersJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(markersJson, new TypeReference<List<MarkerAggregate>>() {
            });
        } catch (Exception exception) {
            log.warn("Cannot deserialize state report markers: {}", exception.getMessage());
            return List.of();
        }
    }

    private static int clamp(Integer value, int min, int max) {
        return Math.max(min, Math.min(value == null ? min : value, max));
    }

    private static String trim(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record CreateReportRequest(
            Long learningSessionId,
            String contextType,
            String contextKey,
            String contextTitle,
            Integer sampleCount,
            Integer durationSeconds,
            Integer aggregateScore,
            Boolean sufficient,
            String headline,
            String description,
            List<MarkerAggregate> markers
    ) {
    }

    /** 按标记（如题目 ID）聚合的信号摘要，用于题卷报告交叉展示。 */
    public record MarkerAggregate(
            String marker,
            Integer sampleCount,
            Integer averageScore
    ) {
    }

    public record ReportView(
            Long id,
            Long learningSessionId,
            String contextType,
            String contextKey,
            String contextTitle,
            int sampleCount,
            int durationSeconds,
            Integer aggregateScore,
            boolean sufficient,
            String headline,
            String description,
            List<MarkerAggregate> markers,
            LocalDateTime createdAt
    ) {
    }
}
