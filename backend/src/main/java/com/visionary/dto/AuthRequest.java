package com.visionary.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 认证请求 DTO
 * 
 * 包含登录和注册两种场景的数据验证
 */
public sealed interface AuthRequest {

    /**
     * 用户登录请求
     */
    record LoginRequest(
            @NotBlank(message = "用户名不能为空")
            @Size(min = 2, max = 64, message = "用户名长度必须在 2-64 之间")
            String username,

            @NotBlank(message = "密码不能为空")
            @Size(min = 6, max = 128, message = "密码长度必须在 6-128 之间")
            String password,

            /**
             * 可选的游客ID，用于登录后数据迁移
             */
            String guestId
    ) implements AuthRequest {}

    /**
     * 用户注册请求
     */
    record RegisterRequest(
            @NotBlank(message = "用户名不能为空")
            @Size(min = 2, max = 12, message = "用户名长度必须在 2-12 个字符之间")
            @Pattern(regexp = "^[\\p{L}\\p{N}_]+$", message = "用户名只能包含中文、字母、数字和下划线")
            String username,

            @NotBlank(message = "密码不能为空")
            @Size(min = 8, max = 128, message = "密码长度必须在 8-128 之间")
            String password,

            @Email(message = "邮箱格式不正确")
            @Size(max = 128, message = "邮箱长度不能超过 128")
            String email,

            @Size(max = 64, message = "显示名称长度不能超过 64")
            String displayName,

            /**
             * 学习偏好：学习方向、年级等
             */
            @Size(max = 256, message = "学习偏好长度不能超过 256")
            String learningPreference,

            @NotBlank(message = "请完成图形验证码")
            String captchaId,

            @NotBlank(message = "请输入图形验证码")
            @Size(min = 4, max = 8, message = "图形验证码格式不正确")
            String captchaAnswer,

            @jakarta.validation.constraints.AssertTrue(message = "请阅读并同意用户协议与隐私说明")
            Boolean termsAccepted,

            /**
             * 可选的游客ID，用于注册后数据迁移
             */
            String guestId
    ) implements AuthRequest {

        /**
         * QQ 邮箱的账号部分应为 QQ 号码。这里只做格式校验，不发送验证码或验证邮件。
         */
        @jakarta.validation.constraints.AssertTrue(message = "QQ邮箱格式不正确，@qq.com 前应为 5-12 位数字")
        public boolean isQqEmailFormatValid() {
            if (email == null || email.isBlank()) {
                return true;
            }

            String normalized = email.trim();
            int separator = normalized.lastIndexOf('@');
            if (separator < 0 || !normalized.substring(separator + 1).equalsIgnoreCase("qq.com")) {
                return true;
            }

            return normalized.substring(0, separator).matches("[1-9]\\d{4,11}");
        }
    }

    /**
     * 创建游客会话请求
     */
    record GuestCreateRequest(
            /**
             * 设备指纹（可选）
             */
            String deviceFingerprint,

            /**
             * 客户端上下文信息（JSON 格式）
             */
            String contextJson
    ) implements AuthRequest {}

    record GuestSnapshotRequest(
            @NotBlank(message = "游客学习快照不能为空")
            @Size(max = 6_000_000, message = "游客学习快照不能超过 6MB")
            String contextJson
    ) implements AuthRequest {}

    /**
     * Token 刷新请求
     */
    record RefreshTokenRequest(
            @NotBlank(message = "刷新令牌不能为空")
            String refreshToken
    ) implements AuthRequest {}

    record PasswordResetRequest(
            @NotBlank(message = "邮箱不能为空")
            @Email(message = "邮箱格式不正确")
            @Size(max = 128, message = "邮箱长度不能超过 128")
            String email
    ) implements AuthRequest {}

    record PasswordResetConfirm(
            @NotBlank(message = "重置令牌不能为空")
            @Size(min = 32, max = 256, message = "重置令牌格式不正确")
            String token,

            @NotBlank(message = "新密码不能为空")
            @Size(min = 8, max = 128, message = "密码长度必须在 8-128 之间")
            String newPassword
    ) implements AuthRequest {}
}
