CREATE TABLE IF NOT EXISTS learning_evidence_link (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    learning_session_id BIGINT NULL,
    evidence_type VARCHAR(32) NOT NULL,
    content_id VARCHAR(128) NULL,
    section_id VARCHAR(128) NULL,
    paper_id VARCHAR(128) NULL,
    question_id VARCHAR(128) NULL,
    attempt_id VARCHAR(128) NULL,
    state_report_id VARCHAR(128) NULL,
    ai_context_key VARCHAR(255) NULL,
    report_id VARCHAR(128) NULL,
    practice_id VARCHAR(128) NULL,
    payload_json LONGTEXT NULL,
    gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_evidence_user_created (user_id, gmt_create),
    INDEX idx_evidence_session (learning_session_id),
    INDEX idx_evidence_content_section (content_id, section_id),
    INDEX idx_evidence_paper_attempt (paper_id, attempt_id),
    INDEX idx_evidence_question (question_id),
    INDEX idx_evidence_report (report_id),
    INDEX idx_evidence_ai_context (ai_context_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Historical VIDEO_SCRIPT rows remain readable, but are explicitly converted to the
-- local animation/text compatibility path so no media task will be resumed or polled.
UPDATE generated_artifact
SET media_task_id = NULL,
    media_status = 'RETIRED',
    media_error = 'Cloud video generation retired; render as local animation or text explanation'
WHERE artifact_type = 'VIDEO_SCRIPT'
  AND (media_status IS NULL OR media_status NOT IN ('RETIRED'));
