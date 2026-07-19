package com.visionary.dto;

import com.visionary.entity.User;

public record UserRequest(
        String username,
        String email,
        String displayName,
        String avatarUrl,
        String gradeLevel,
        String learningGoal,
        String learnerProfileSnapshot,
        String emotionProfileSnapshot,
        User.UserStatus status
) {
}
