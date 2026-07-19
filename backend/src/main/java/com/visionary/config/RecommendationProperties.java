package com.visionary.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "visionary.recommendation")
public class RecommendationProperties {

    /**
     * 是否启用定时主动推荐推送。
     */
    private boolean scheduledPushEnabled = true;

    /**
     * 定时推送间隔（毫秒），默认每 6 小时。
     */
    private long scheduledPushIntervalMs = 21_600_000L;

    /**
     * 参与定时推送的最近活跃会话窗口（小时）。
     */
    private int activeSessionWindowHours = 72;
}
