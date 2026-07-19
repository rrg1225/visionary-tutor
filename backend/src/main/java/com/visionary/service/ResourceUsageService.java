package com.visionary.service;

import com.visionary.entity.ResourceUsageRecord;
import com.visionary.repository.ResourceUsageRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceUsageService {

    private final ResourceUsageRecordRepository usageRepository;
    private final LearningEffectAssessmentService learningEffectAssessmentService;

    @Transactional
    public void recordUsage(
            Long userId,
            Long learningSessionId,
            Long resourceId,
            String actionType,
            Integer durationSeconds,
            String feedback
    ) {
        if (userId == null || actionType == null || actionType.isBlank()) {
            return;
        }
        ResourceUsageRecord record = new ResourceUsageRecord();
        record.setUserId(userId);
        record.setLearningSessionId(learningSessionId);
        record.setResourceId(resourceId);
        record.setActionType(actionType.trim().toLowerCase());
        record.setDurationSeconds(durationSeconds);
        record.setFeedback(feedback);
        usageRepository.save(record);

        if ("view".equalsIgnoreCase(actionType) && resourceId != null) {
            try {
                learningEffectAssessmentService.recordMetric(
                        userId,
                        learningSessionId,
                        "RESOURCE_VIEW",
                        "artifact:" + resourceId,
                        durationSeconds != null ? durationSeconds.doubleValue() : 1.0D,
                        feedback,
                        "resource_card_view"
                );
            } catch (Exception e) {
                log.warn("RESOURCE_VIEW metric mirror skipped: {}", e.getMessage());
            }
        }
    }
}
