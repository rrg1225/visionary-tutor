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
 * 共享教材库（UGC）— 用户贡献的可检索学习材料。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "shared_textbook")
public class SharedTextbook extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "content_markdown", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String contentMarkdown;

    @Column(name = "subject_tag", length = 64)
    private String subjectTag = "computer-vision";

    @Column(name = "source_type", nullable = false, length = 32)
    private String sourceType = "original";

    @Column(name = "source_title", length = 255)
    private String sourceTitle;

    @Column(name = "source_url", length = 1000)
    private String sourceUrl;

    @Column(name = "license_name", length = 128)
    private String licenseName;

    @Column(name = "rights_statement", length = 1000)
    private String rightsStatement;

    @Column(name = "rights_confirmed", nullable = false)
    private Boolean rightsConfirmed = false;

    @Column(name = "visibility", nullable = false, length = 32)
    private String visibility = "public";

    @Column(name = "review_status", nullable = false, length = 32)
    private String reviewStatus = "pending";

    @Column(name = "ai_review_status", nullable = false, length = 32)
    private String aiReviewStatus = "not_scanned";

    @Column(name = "ai_risk_level", length = 16)
    private String aiRiskLevel;

    @Column(name = "ai_review_reason", length = 1000)
    private String aiReviewReason;

    @Column(name = "ai_reviewed_at")
    private java.time.LocalDateTime aiReviewedAt;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private java.time.LocalDateTime reviewedAt;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "view_count", nullable = false)
    private Integer viewCount = 0;
}
