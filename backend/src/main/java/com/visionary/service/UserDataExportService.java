package com.visionary.service;

import com.visionary.entity.GeneratedArtifact;
import com.visionary.entity.LearningEventMetric;
import com.visionary.entity.LearningSession;
import com.visionary.entity.MemoryUpdateLog;
import com.visionary.entity.ResourceUsageRecord;
import com.visionary.entity.SessionChatMessage;
import com.visionary.entity.User;
import com.visionary.entity.UserMemory;
import com.visionary.repository.GeneratedArtifactRepository;
import com.visionary.repository.LearningEventMetricRepository;
import com.visionary.repository.LearningSessionRepository;
import com.visionary.repository.MemoryUpdateLogRepository;
import com.visionary.repository.ResourceUsageRecordRepository;
import com.visionary.repository.SessionChatMessageRepository;
import com.visionary.repository.UserMemoryRepository;
import com.visionary.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserDataExportService {

    private final UserRepository userRepository;
    private final UserMemoryRepository memoryRepository;
    private final MemoryUpdateLogRepository memoryUpdateLogRepository;
    private final LearningSessionRepository learningSessionRepository;
    private final SessionChatMessageRepository chatMessageRepository;
    private final GeneratedArtifactRepository artifactRepository;
    private final LearningEventMetricRepository metricRepository;
    private final ResourceUsageRecordRepository usageRepository;

    @Transactional(readOnly = true)
    public UserDataExport exportUserData(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        List<LearningSession> sessions = learningSessionRepository.findByUserIdOrderByGmtCreatedDesc(userId);
        List<SessionBundle> sessionBundles = sessions.stream().map(this::toSessionBundle).toList();
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("id", user.getId());
        account.put("username", user.getUsername());
        account.put("email", user.getEmail());
        account.put("displayName", user.getDisplayName());
        account.put("gradeLevel", user.getGradeLevel());
        account.put("learningGoal", user.getLearningGoal());
        account.put("status", user.getStatus() != null ? user.getStatus().name() : "");
        return new UserDataExport(
                Instant.now().toString(),
                account,
                user.getLearnerProfileSnapshot(),
                user.getEmotionProfileSnapshot(),
                memoryRepository.findByUserIdOrderByGmtModifiedDesc(userId),
                memoryUpdateLogRepository.findByUserIdOrderByGmtCreatedDesc(userId),
                sessionBundles,
                metricRepository.findByUserIdOrderByEventTimeDesc(userId),
                usageRepository.findByUserIdOrderByGmtCreatedDesc(userId),
                privacyPolicy()
        );
    }

    @Transactional
    public MemoryDeleteResult deleteLearningMemories(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        List<UserMemory> memories = memoryRepository.findByUserIdOrderByGmtModifiedDesc(userId);
        int activeCount = 0;
        for (UserMemory memory : memories) {
            if (Boolean.TRUE.equals(memory.getIsActive())) {
                activeCount++;
            }
            memory.setIsActive(false);
            memory.setReviewStatus("DELETED_BY_USER");
        }
        memoryRepository.saveAll(memories);
        user.setLearnerProfileSnapshot(null);
        user.setEmotionProfileSnapshot(null);
        user.setLastPolicyReason("User deleted learning memory at " + Instant.now());
        userRepository.save(user);
        return new MemoryDeleteResult(userId, activeCount, memories.size(), Instant.now().toString());
    }

    /**
     * Permanently disables login and removes directly identifying/profile data.
     * Learning rows retain only the anonymized internal id for integrity/audit retention.
     */
    @Transactional
    public AccountDeleteResult deleteAccount(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        MemoryDeleteResult memoryResult = deleteLearningMemories(userId);
        String tombstone = "deleted-" + userId + "-" + UUID.randomUUID().toString().substring(0, 8);
        user.setUsername(tombstone);
        user.setEmail(null);
        user.setDisplayName("已注销用户");
        user.setAvatarUrl(null);
        user.setGradeLevel(null);
        user.setLearningGoal(null);
        user.setLearnerProfileSnapshot(null);
        user.setEmotionProfileSnapshot(null);
        user.setPreviousGuestId(null);
        user.setPassword("ACCOUNT_DELETED_" + UUID.randomUUID());
        user.setStatus(User.UserStatus.INACTIVE);
        user.setLastPolicyReason("Account deleted and personal identifiers erased at " + Instant.now());
        userRepository.save(user);
        return new AccountDeleteResult(userId, true, memoryResult.activeMemoriesDeleted(), Instant.now().toString());
    }

    public Map<String, Object> privacyPolicy() {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("cameraDefaultEnabled", false);
        policy.put("rawVideoUploaded", false);
        policy.put("uploadedSignals", List.of("confusion", "attention_drop", "local_profile_snapshot"));
        policy.put("memoryReview", "User can view, approve, reject, manually edit, export, and delete learning memories.");
        policy.put("ugcReview", "Shared textbooks require admin review before entering public RAG retrieval.");
        return policy;
    }

    private SessionBundle toSessionBundle(LearningSession session) {
        List<GeneratedArtifact> artifacts = artifactRepository.findByLearningSessionIdOrderByGmtCreatedDesc(session.getId());
        List<SessionChatMessage> messages = chatMessageRepository.findByLearningSessionIdOrderBySeqAsc(session.getId());
        return new SessionBundle(session, messages, artifacts);
    }

    public record UserDataExport(
            String exportedAt,
            Map<String, Object> user,
            String learnerProfileSnapshot,
            String emotionProfileSnapshot,
            List<UserMemory> memories,
            List<MemoryUpdateLog> memoryAuditLogs,
            List<SessionBundle> sessions,
            List<LearningEventMetric> learningMetrics,
            List<ResourceUsageRecord> resourceUsage,
            Map<String, Object> privacyPolicy
    ) {
    }

    public record SessionBundle(
            LearningSession session,
            List<SessionChatMessage> chatMessages,
            List<GeneratedArtifact> artifacts
    ) {
    }

    public record MemoryDeleteResult(
            Long userId,
            int activeMemoriesDeleted,
            int totalMemoryRowsTouched,
            String deletedAt
    ) {
    }

    public record AccountDeleteResult(
            Long userId,
            boolean accountDisabled,
            int activeMemoriesDeleted,
            String deletedAt
    ) {
    }
}
