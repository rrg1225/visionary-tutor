package com.visionary.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "generated_artifact")
public class GeneratedArtifact extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "learning_session_id", nullable = false)
    private Long learningSessionId;

    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;

    @Enumerated(EnumType.STRING)
    @Column(name = "artifact_type", nullable = false, length = 32)
    private ArtifactType artifactType;

    @Column(name = "title", nullable = false, length = 128)
    private String title;

    @Column(name = "content_markdown", columnDefinition = "LONGTEXT")
    private String contentMarkdown;

    @Column(name = "content_json", columnDefinition = "LONGTEXT")
    private String contentJson;

    @Column(name = "citations_json", columnDefinition = "TEXT")
    private String citationsJson;

    @Column(name = "validation_status", nullable = false, length = 32)
    private String validationStatus = "UNVERIFIED";

    @Column(name = "publish_status", nullable = false, length = 32)
    private String publishStatus = "PUBLISHED";

    @Column(name = "verification_audit_json", columnDefinition = "TEXT")
    private String verificationAuditJson;

    @Column(name = "review_notes", columnDefinition = "TEXT")
    private String reviewNotes;

    @Column(name = "progress")
    private Integer progress = 100;

    @Column(name = "media_task_id", length = 128)
    private String mediaTaskId;

    @Column(name = "media_status", length = 32)
    private String mediaStatus;

    @Column(name = "media_url", columnDefinition = "TEXT")
    private String mediaUrl;

    @Column(name = "cover_image_url", columnDefinition = "TEXT")
    private String coverImageUrl;

    @Column(name = "media_error", columnDefinition = "TEXT")
    private String mediaError;

    /**
     * 媒体生成任务轮询重试次数。
     * 用于实现指数退避和最大重试次数限制。
     */
    @Column(name = "poll_retry_count")
    private Integer pollRetryCount = 0;

    public enum ArtifactType {
        HANDOUT,
        QUIZ,
        MINDMAP,
        LEARNING_PATH,
        CODE_PRACTICE,
        EXTENDED_READING,
        VIDEO_SCRIPT,
        VISUALIZATION
    }

    public void setContent(String content) {
        this.contentMarkdown = content;
    }

    public void setCitations(String citations) {
        this.citationsJson = citations;
    }

    public void setStatus(String status) {
        this.validationStatus = status;
    }
}
