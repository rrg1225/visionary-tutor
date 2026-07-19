package com.visionary.os;

import com.visionary.entity.User;
import com.visionary.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LearnerStateStore {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public LearnerStateView getState(Long userId) {
        if (userId == null) {
            return LearnerStateView.empty();
        }
        return userRepository.findById(userId)
                .map(this::toView)
                .orElse(LearnerStateView.empty());
    }

    @Transactional
    public LearnerStateView bumpProfileVersion(Long userId, String snapshot, String reason) {
        if (userId == null) {
            return LearnerStateView.empty();
        }
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return LearnerStateView.empty();
        }
        if (snapshot != null && !snapshot.isBlank()) {
            user.setLearnerProfileSnapshot(snapshot);
        }
        user.setProfileVersion(user.getProfileVersion() + 1);
        if (reason != null && !reason.isBlank()) {
            user.setLastPolicyReason(reason);
        }
        userRepository.save(user);
        return toView(user);
    }

    @Transactional
    public LearnerStateView bumpPathVersion(Long userId, String reason) {
        if (userId == null) {
            return LearnerStateView.empty();
        }
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return LearnerStateView.empty();
        }
        user.setPathVersion(user.getPathVersion() + 1);
        if (reason != null && !reason.isBlank()) {
            user.setLastPolicyReason(reason);
        }
        userRepository.save(user);
        return toView(user);
    }

    @Transactional
    public void recordPolicyReason(Long userId, String reason) {
        if (userId == null || reason == null || reason.isBlank()) {
            return;
        }
        userRepository.findById(userId).ifPresent(user -> {
            user.setLastPolicyReason(reason);
            userRepository.save(user);
        });
    }

    private LearnerStateView toView(User user) {
        return new LearnerStateView(
                user.getId(),
                user.getProfileVersion(),
                user.getPathVersion(),
                user.getLearnerProfileSnapshot(),
                user.getLearningGoal(),
                user.getLastPolicyReason(),
                user.getGmtModified() != null ? user.getGmtModified().toString() : null
        );
    }

    public record LearnerStateView(
            Long userId,
            int profileVersion,
            int pathVersion,
            String profileSnapshot,
            String learningGoal,
            String lastPolicyReason,
            String updatedAt
    ) {
        public static LearnerStateView empty() {
            return new LearnerStateView(null, 0, 0, "{}", null, null, null);
        }
    }
}
