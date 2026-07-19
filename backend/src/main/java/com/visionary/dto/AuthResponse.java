package com.visionary.dto;

import com.visionary.entity.User;

import java.time.LocalDateTime;

/**
 * 认证响应 DTO
 * 
 * 统一返回格式，支持游客和正式用户两种场景
 */
public sealed interface AuthResponse {

    /**
     * 登录/注册成功响应
     */
    record Success(
            /**
             * JWT Token
             */
            String token,

            /**
             * Token 类型：Bearer
             */
            String tokenType,

            /**
             * Token 过期时间（秒）
             */
            Long expiresIn,

            /**
             * 是否为游客
             */
            Boolean isGuest,

            /**
             * 用户信息
             */
            UserInfo user,

            /**
             * 数据迁移结果（如果有）
             */
            MigrationInfo migration
    ) implements AuthResponse {}

    /**
     * 游客会话创建成功响应
     */
    record GuestSuccess(
            /**
             * 游客 JWT Token
             */
            String token,

            /**
             * Token 类型：Bearer
             */
            String tokenType,

            /**
             * Token 过期时间（秒）
             */
            Long expiresIn,

            /**
             * 游客唯一标识
             */
            String guestId,

            /**
             * 游客会话过期时间
             */
            LocalDateTime sessionExpiresAt,

            /**
             * Redis 中的对话配额（免费 3 次 / 会话窗口）
             */
            GuestChatQuotaInfo chatQuota
    ) implements AuthResponse {}

    /**
     * 游客对话配额（与 GuestSessionService.GuestChatQuota 对齐）
     */
    record GuestChatQuotaInfo(
            int usedTurns,
            int maxTurns,
            int remainingTurns,
            long sessionTtlSeconds
    ) {}

    /**
     * 认证失败响应
     */
    record Error(
            /**
             * 错误码
             */
            String code,

            /**
             * 错误消息
             */
            String message,

            /**
             * 详细错误信息（开发环境）
             */
            String details
    ) implements AuthResponse {}

    /**
     * 用户信息子对象
     */
    record UserInfo(
            Long id,
            String username,
            String email,
            String displayName,
            String avatarUrl,
            String gradeLevel,
            String learningGoal,
            User.UserStatus status
    ) {
        public static UserInfo fromEntity(User user) {
            return new UserInfo(
                    user.getId(),
                    user.getUsername(),
                    user.getEmail(),
                    user.getDisplayName(),
                    user.getAvatarUrl(),
                    user.getGradeLevel(),
                    user.getLearningGoal(),
                    user.getStatus()
            );
        }
    }

    /**
     * 数据迁移信息
     */
    record MigrationInfo(
            /**
             * 是否执行了数据迁移
             */
            Boolean migrated,

            /**
             * 原游客ID
             */
            String fromGuestId,

            /**
             * 迁移的学习会话数量
             */
            Integer migratedSessionsCount,

            /**
             * 迁移的诊断报告数量
             */
            Integer migratedReportsCount
    ) {}
}
