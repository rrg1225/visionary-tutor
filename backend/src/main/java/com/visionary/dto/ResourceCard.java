package com.visionary.dto;

import lombok.Builder;
import lombok.Value;

/**
 * Frontend-facing resource card with optional grounding audit proxies.
 */
@Value
@Builder(toBuilder = true)
public class ResourceCard {

    Long id;
    Long learningSessionId;
    String runId;
    String artifactType;
    String title;
    String contentMarkdown;
    String contentJson;
    String citationsJson;
    String validationStatus;
    String publishStatus;
    String verificationAuditJson;
    String reviewNotes;
    Integer progress;
    String mediaTaskId;
    String mediaStatus;
    String mediaUrl;
    String coverImageUrl;
    String mediaError;
    GroundingMetrics groundingMetrics;
    /** 内置示例资源，供空库预览 */
    @Builder.Default
    Boolean showcase = false;
    /** 所属学习会话主题（列表展示用） */
    String sessionTopic;
}
