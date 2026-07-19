package com.visionary.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 推荐结果审计与主动推送日志。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "resource_recommendation_log")
public class ResourceRecommendationLog extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "learning_session_id")
    private Long learningSessionId;

    @Column(name = "query_text", columnDefinition = "TEXT")
    private String queryText;

    @Column(name = "recommended_ids", length = 512)
    private String recommendedIds;

    @Column(name = "is_fallback")
    private Boolean isFallback;

    @Column(name = "push_source", length = 32)
    private String pushSource;

    @Column(name = "push_message", length = 512)
    private String pushMessage;

    @Column(name = "consumed", nullable = false)
    private Boolean consumed = false;
}
