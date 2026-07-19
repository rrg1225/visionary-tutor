package com.visionary.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "visionary.learning-os")
public class LearningOsProperties {

    /** When true, quiz/assessment remediation runs via Redis queue + worker. */
    private boolean asyncRemediation = true;

    private String remediationQueueKey = "visionary:learning-os:remediation";
}
