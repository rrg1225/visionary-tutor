package com.visionary.service;

import com.visionary.config.RecommendationProperties;
import com.visionary.entity.LearningSession;
import com.visionary.repository.LearningSessionRepository;
import com.visionary.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 定时主动资源推荐 — 对近期活跃会话的学习者推送个性化资源。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceRecommendationScheduler {

    private final RecommendationProperties properties;
    private final LearningSessionRepository learningSessionRepository;
    private final UserRepository userRepository;
    private final RecommendationPushService recommendationPushService;

    @Scheduled(fixedDelayString = "${visionary.recommendation.scheduled-push-interval-ms:21600000}",
            initialDelayString = "${visionary.recommendation.scheduled-push-initial-delay-ms:120000}")
    public void scheduledRecommendationPush() {
        if (!properties.isScheduledPushEnabled()) {
            return;
        }
        LocalDateTime since = LocalDateTime.now().minusHours(properties.getActiveSessionWindowHours());
        List<LearningSession> sessions = learningSessionRepository.findByGmtModifiedAfterOrderByGmtModifiedDesc(since);
        if (sessions.isEmpty()) {
            log.debug("[RecommendationScheduler] no active sessions since {}", since);
            return;
        }

        Set<Long> pushedUsers = new HashSet<>();
        int pushCount = 0;
        for (LearningSession session : sessions) {
            Long userId = session.getUserId();
            if (userId == null || userId <= 0 || pushedUsers.contains(userId)) {
                continue;
            }
            if (userRepository.findById(userId).isEmpty()) {
                continue;
            }
            String message = "定期学习提醒：根据你的近期学习进度，系统为你更新了推荐资源";
            var pushed = recommendationPushService.pushForSession(
                    userId,
                    session.getId(),
                    "{}",
                    session.getTopic(),
                    null,
                    false,
                    "scheduled",
                    message
            );
            if (pushed.isPresent()) {
                pushedUsers.add(userId);
                pushCount++;
            }
        }
        if (pushCount > 0) {
            log.info("[RecommendationScheduler] pushed recommendations to {} learners", pushCount);
        }
    }
}
