package com.visionary.dto;

import com.visionary.entity.User;

public record UserProfileDto(
        Long id,
        String username,
        String email,
        String displayName,
        String avatarUrl,
        String gradeLevel,
        String learningGoal,
        Integer profileVersion,
        Integer pathVersion,
        User.UserStatus status
) {
    public static UserProfileDto fromEntity(User user) {
        return new UserProfileDto(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getDisplayName(),
                user.getAvatarUrl(),
                user.getGradeLevel(),
                user.getLearningGoal(),
                user.getProfileVersion(),
                user.getPathVersion(),
                user.getStatus()
        );
    }
}
