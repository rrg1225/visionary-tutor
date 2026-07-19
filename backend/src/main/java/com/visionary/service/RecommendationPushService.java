package com.visionary.service;

import com.visionary.dto.RecommendationPushDto;
import com.visionary.dto.ResourceRecommendationDto;
import com.visionary.dto.ResourceRecommendationResponse;
import com.visionary.entity.GeneratedArtifact;
import com.visionary.entity.ResourceRecommendationLog;
import com.visionary.notification.NotificationPublisher;
import com.visionary.notification.NotificationType;
import com.visionary.repository.GeneratedArtifactRepository;
import com.visionary.repository.ResourceRecommendationLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 主动资源推荐推送 — 事件触发与定时任务共用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationPushService {

    private final ResourceRecommendationService recommendationService;
    private final ResourceRecommendationLogRepository recommendationLogRepository;
    private final GeneratedArtifactRepository artifactRepository;
    private final NotificationPublisher notificationPublisher;

    @Transactional
    public Optional<RecommendationPushDto> pushForSession(
            Long userId,
            Long learningSessionId,
            String learnerProfileSnapshot,
            String weakPointsSnapshot,
            String cognitiveStyle,
            boolean recentQuizLow,
            String pushSource,
            String pushMessage
    ) {
        if (userId == null || learningSessionId == null) {
            return Optional.empty();
        }
        List<GeneratedArtifact> artifacts = artifactRepository.findByLearningSessionIdOrderByGmtCreatedDesc(learningSessionId);
        if (artifacts.isEmpty()) {
            log.debug("[RecommendationPush] skip empty session userId={} sessionId={}", userId, learningSessionId);
            return Optional.empty();
        }

        ResourceRecommendationResponse response = recommendationService.recommend(
                artifacts,
                learnerProfileSnapshot,
                weakPointsSnapshot,
                cognitiveStyle,
                recentQuizLow,
                userId,
                learningSessionId,
                pushSource,
                pushMessage
        );
        if (response.recommendations().isEmpty()) {
            return Optional.empty();
        }

        ResourceRecommendationLog saved = recommendationLogRepository
                .findFirstByUserIdAndConsumedFalseOrderByGmtCreatedDesc(userId)
                .orElse(null);

        Long pushId = saved != null ? saved.getId() : null;
        log.info("[RecommendationPush] pushed {} items for userId={} sessionId={} source={}",
                response.recommendations().size(), userId, learningSessionId, pushSource);

        RecommendationPushDto pushDto = new RecommendationPushDto(
                pushId,
                learningSessionId,
                pushMessage,
                pushSource,
                response.recommendations()
        );
        publishRealtimePush(userId, pushDto);
        return Optional.of(pushDto);
    }

    private void publishRealtimePush(Long userId, RecommendationPushDto pushDto) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pushId", pushDto.pushId());
        payload.put("learningSessionId", pushDto.learningSessionId());
        payload.put("message", pushDto.message());
        payload.put("pushSource", pushDto.pushSource());
        payload.put("recommendations", pushDto.recommendations());
        notificationPublisher.publish(userId, NotificationType.RECOMMENDATION_PUSH, payload);
    }

    @Transactional(readOnly = true)
    public Optional<RecommendationPushDto> getPendingPush(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return recommendationLogRepository.findFirstByUserIdAndConsumedFalseOrderByGmtCreatedDesc(userId)
                .map(this::toPushDto);
    }

    @Transactional
    public void markConsumed(Long pushId, Long userId) {
        if (pushId == null || userId == null) {
            return;
        }
        recommendationLogRepository.findById(pushId).ifPresent(log -> {
            if (userId.equals(log.getUserId())) {
                log.setConsumed(true);
                recommendationLogRepository.save(log);
            }
        });
    }

    private RecommendationPushDto toPushDto(ResourceRecommendationLog log) {
        List<ResourceRecommendationDto> items = parseRecommendedIds(log.getRecommendedIds(), log.getLearningSessionId());
        return new RecommendationPushDto(
                log.getId(),
                log.getLearningSessionId(),
                log.getPushMessage(),
                log.getPushSource(),
                items
        );
    }

    private List<ResourceRecommendationDto> parseRecommendedIds(String idsCsv, Long sessionId) {
        if (idsCsv == null || idsCsv.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(idsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(Long::parseLong)
                .map(id -> artifactRepository.findById(id)
                        .map(a -> new ResourceRecommendationDto(
                                a.getId(),
                                a.getArtifactType() != null ? a.getArtifactType().name() : "UNKNOWN",
                                a.getTitle(),
                                "主动推送推荐",
                                80
                        ))
                        .orElse(new ResourceRecommendationDto(id, "UNKNOWN", "资源 #" + id, "主动推送推荐", 80)))
                .toList();
    }
}
