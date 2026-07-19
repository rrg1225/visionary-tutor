package com.visionary.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.dto.MemoryUpdateLogDto;
import com.visionary.dto.UserMemoryDto;
import com.visionary.entity.MemoryUpdateLog;
import com.visionary.entity.User;
import com.visionary.entity.UserMemory;
import com.visionary.repository.MemoryUpdateLogRepository;
import com.visionary.repository.UserMemoryRepository;
import com.visionary.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserMemoryService {

    private static final String[] PROFILE_DIMENSION_KEYS = {
            "knowledgeBase", "goal", "cognitiveStyle", "weakPoints",
            "errorPatterns", "learningPace", "emotionAttention"
    };

    private static final Map<String, String> DIMENSION_MEMORY_TYPE = Map.ofEntries(
            Map.entry("knowledgeBase", "profile"),
            Map.entry("goal", "goal"),
            Map.entry("cognitiveStyle", "preference"),
            Map.entry("weakPoints", "weak_point"),
            Map.entry("errorPatterns", "mistake"),
            Map.entry("learningPace", "preference"),
            Map.entry("emotionAttention", "profile")
    );

    private final UserMemoryRepository userMemoryRepository;
    private final MemoryUpdateLogRepository memoryUpdateLogRepository;
    private final MemoryReviewService memoryReviewService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<UserMemoryDto> listActiveMemories(Long userId) {
        return userMemoryRepository.findByUserIdAndIsActiveTrueOrderByPriorityDescGmtModifiedDesc(userId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<UserMemoryDto> listPendingReview(Long userId) {
        return userMemoryRepository.findByUserIdAndReviewStatusOrderByGmtModifiedDesc(userId, "pending")
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MemoryUpdateLogDto> listUpdateLogs(Long userId) {
        return memoryUpdateLogRepository.findByUserIdOrderByGmtCreatedDesc(userId)
                .stream()
                .limit(50)
                .map(this::toLogDto)
                .toList();
    }

    @Transactional
    public UserMemoryDto upsertManualMemory(Long userId, String memoryType, String memoryKey, String memoryValue) {
        return applyMemoryUpdate(
                userId,
                null,
                memoryType,
                memoryKey,
                memoryValue,
                "manual",
                null,
                100,
                "用户手动更新记忆",
                null,
                true,
                1.0D
        );
    }

    @Transactional
    public UserMemoryDto approveMemory(Long userId, Long memoryId) {
        UserMemory memory = requireOwnedMemory(userId, memoryId);
        memory.setReviewStatus("approved");
        memory.setIsActive(true);
        return toDto(userMemoryRepository.save(memory));
    }

    @Transactional
    public UserMemoryDto rejectMemory(Long userId, Long memoryId) {
        UserMemory memory = requireOwnedMemory(userId, memoryId);
        memory.setReviewStatus("rejected");
        memory.setIsActive(false);
        return toDto(userMemoryRepository.save(memory));
    }

    @Transactional
    public void syncFromProfileSnapshot(Long userId, Long learningSessionId, String profileSnapshot, String sourceContext) {
        if (userId == null || profileSnapshot == null || profileSnapshot.isBlank()) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(profileSnapshot);
            String profileSummary = summarizeProfile(root);
            for (String key : PROFILE_DIMENSION_KEYS) {
                JsonNode dim = root.path(key);
                if (!dim.isObject()) {
                    continue;
                }
                String value = dim.path("value").asText("");
                if (!isInformative(value)) {
                    continue;
                }
                double confidence = dim.path("confidence").asDouble(0.55D);
                String memoryType = DIMENSION_MEMORY_TYPE.getOrDefault(key, "profile");
                applyMemoryUpdate(
                        userId,
                        learningSessionId,
                        memoryType,
                        key,
                        value,
                        "chat",
                        null,
                        60,
                        "画像抽取同步",
                        buildReviewContext(profileSummary, sourceContext, dim),
                        false,
                        confidence
                );
            }
            JsonNode knowledgeState = root.path("knowledgeState");
            if (knowledgeState.isArray()) {
                for (JsonNode item : knowledgeState) {
                    String concept = item.path("concept").asText("");
                    if (!isInformative(concept)) {
                        continue;
                    }
                    int mastery = item.path("mastery").asInt(0);
                    String value = concept + " · 掌握度 " + mastery + "%";
                    applyMemoryUpdate(
                            userId,
                            learningSessionId,
                            "progress",
                            "ks:" + concept,
                            value,
                            "progress",
                            null,
                            55,
                            "知识状态同步",
                            sourceContext,
                            false,
                            item.path("confidence").asDouble(0.7D)
                    );
                }
            }
        } catch (Exception e) {
            log.warn("Profile snapshot memory sync skipped for user {}: {}", userId, e.getMessage());
        }
    }

    private UserMemoryDto applyMemoryUpdate(
            Long userId,
            Long learningSessionId,
            String memoryType,
            String memoryKey,
            String memoryValue,
            String sourceType,
            Long sourceId,
            int priority,
            String reason,
            String sourceContext,
            boolean skipReview,
            double dimensionConfidence
    ) {
        Optional<UserMemory> existingOpt = userMemoryRepository.findByUserIdAndMemoryTypeAndMemoryKey(
                userId, memoryType, memoryKey
        );
        String oldValue = existingOpt.map(UserMemory::getMemoryValue).orElse(null);

        if (existingOpt.isPresent()) {
            UserMemory existing = existingOpt.get();
            if (existing.getPriority() != null && existing.getPriority() > priority) {
                return toDto(existing);
            }
            if (oldValue != null && oldValue.equals(memoryValue)) {
                return toDto(existing);
            }
        }

        String profileSummary = userRepository.findById(userId)
                .map(User::getLearnerProfileSnapshot)
                .orElse("");
        MemoryReviewService.ReviewResult review = skipReview
                ? new MemoryReviewService.ReviewResult(true, 1.0D, "manual override", 0, false)
                : memoryReviewService.reviewMemoryUpdate(profileSummary, sourceContext, memoryValue, memoryKey);

        UserMemory memory = existingOpt.orElseGet(UserMemory::new);
        memory.setUserId(userId);
        memory.setLearningSessionId(learningSessionId);
        memory.setMemoryType(memoryType);
        memory.setMemoryKey(memoryKey);
        memory.setMemoryValue(memoryValue);
        memory.setSourceType(sourceType);
        memory.setSourceId(sourceId);
        memory.setPriority(priority);
        double mergedConfidence = skipReview
                ? dimensionConfidence
                : Math.min(review.score(), Math.max(0.0D, dimensionConfidence));
        memory.setConfidenceScore(BigDecimal.valueOf(mergedConfidence).setScale(2, RoundingMode.HALF_UP));
        memory.setReviewStatus(review.approved() ? "approved" : "pending");
        memory.setIsActive(review.approved());

        UserMemory saved = userMemoryRepository.save(memory);
        logUpdate(userId, learningSessionId, saved.getId(), oldValue, memoryValue, reason, sourceContext, review);
        return toDto(saved);
    }

    private void logUpdate(
            Long userId,
            Long learningSessionId,
            Long memoryId,
            String oldValue,
            String newValue,
            String reason,
            String sourceContext,
            MemoryReviewService.ReviewResult review
    ) {
        MemoryUpdateLog logEntry = new MemoryUpdateLog();
        logEntry.setUserId(userId);
        logEntry.setLearningSessionId(learningSessionId);
        logEntry.setMemoryId(memoryId);
        logEntry.setOldValue(oldValue);
        logEntry.setNewValue(newValue);
        logEntry.setUpdateReason(reason);
        logEntry.setSourceText(truncate(sourceContext, 4000));
        logEntry.setAgentScore(BigDecimal.valueOf(review.score()).setScale(2, RoundingMode.HALF_UP));
        logEntry.setUpdateStatus(review.approved() ? "success" : "pending");
        memoryUpdateLogRepository.save(logEntry);
    }

    private UserMemory requireOwnedMemory(Long userId, Long memoryId) {
        UserMemory memory = userMemoryRepository.findById(memoryId)
                .orElseThrow(() -> new IllegalArgumentException("memory not found"));
        if (!userId.equals(memory.getUserId())) {
            throw new IllegalArgumentException("memory access denied");
        }
        return memory;
    }

    private String summarizeProfile(JsonNode root) {
        List<String> parts = new ArrayList<>();
        for (String key : PROFILE_DIMENSION_KEYS) {
            JsonNode dim = root.path(key);
            if (dim.isObject()) {
                String value = dim.path("value").asText("");
                if (isInformative(value)) {
                    parts.add(key + ": " + value);
                }
            }
        }
        return String.join("\n", parts);
    }

    private String buildReviewContext(String profileSummary, String sourceContext, JsonNode dim) {
        StringBuilder sb = new StringBuilder();
        if (profileSummary != null && !profileSummary.isBlank()) {
            sb.append(profileSummary);
        }
        if (sourceContext != null && !sourceContext.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append("\n");
            }
            sb.append(sourceContext);
        }
        if (dim.has("evidence") && dim.path("evidence").isArray() && !dim.path("evidence").isEmpty()) {
            sb.append("\nevidence: ").append(dim.path("evidence").toString());
        }
        return sb.toString();
    }

    private boolean isInformative(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.trim();
        return !normalized.equals("待观察") && !normalized.equals("unknown") && !normalized.equals("—");
    }

    private UserMemoryDto toDto(UserMemory memory) {
        return new UserMemoryDto(
                memory.getId(),
                memory.getMemoryType(),
                memory.getMemoryKey(),
                memory.getMemoryValue(),
                memory.getSourceType(),
                memory.getPriority(),
                memory.getConfidenceScore(),
                memory.getReviewStatus(),
                memory.getIsActive(),
                memory.getGmtModified()
        );
    }

    private MemoryUpdateLogDto toLogDto(MemoryUpdateLog log) {
        return new MemoryUpdateLogDto(
                log.getId(),
                log.getMemoryId(),
                log.getOldValue(),
                log.getNewValue(),
                log.getUpdateReason(),
                log.getAgentScore(),
                log.getUpdateStatus(),
                log.getGmtCreated()
        );
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

}
