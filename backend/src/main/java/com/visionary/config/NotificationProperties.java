package com.visionary.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "visionary.notification")
public class NotificationProperties {

    private boolean websocketEnabled = true;
    private long heartbeatIntervalMs = 30_000;
}
