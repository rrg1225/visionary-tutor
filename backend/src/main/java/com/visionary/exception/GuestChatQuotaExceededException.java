package com.visionary.exception;

import com.visionary.service.GuestSessionService.GuestChatQuota;
import lombok.Getter;

/**
 * 游客免费对话次数已用尽（默认 3 次 / 1 小时 Redis 会话窗口）。
 */
@Getter
public class GuestChatQuotaExceededException extends RuntimeException {

    public static final String ERROR_CODE = "GUEST_QUOTA_EXCEEDED";

    private final GuestChatQuota quota;

    public GuestChatQuotaExceededException(GuestChatQuota quota) {
        super("Guest free chat quota exceeded");
        this.quota = quota;
    }
}
