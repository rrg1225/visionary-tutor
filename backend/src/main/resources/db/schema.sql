-- VisionaryTutor core schema (MySQL 8+)
-- Naming rule: gmt_created / gmt_modified only (never create_time / update_time)

CREATE DATABASE IF NOT EXISTS visionary_tutor
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE visionary_tutor;

-- ---------------------------------------------------------------------------
-- app_user
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS app_user (
    id                      BIGINT       NOT NULL AUTO_INCREMENT,
    username                VARCHAR(64)  NOT NULL,
    password_hash           VARCHAR(255) NOT NULL DEFAULT '',
    email                   VARCHAR(128) NULL,
    display_name            VARCHAR(64)  NULL,
    avatar_url              VARCHAR(512) NULL,
    grade_level             VARCHAR(32)  NULL,
    learning_goal           VARCHAR(256) NULL,
    emotion_profile_snapshot TEXT        NULL,
    previous_guest_id       VARCHAR(64)  NULL,
    status                  VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    gmt_created             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_user_username (username),
    UNIQUE KEY uk_app_user_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- learning_session
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS learning_session (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    user_id              BIGINT       NOT NULL,
    topic                VARCHAR(256) NOT NULL,
    status               VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    current_phase        VARCHAR(32)  NULL,
    streaming_handout    LONGTEXT     NULL,
    conversation_summary TEXT         NULL,
    last_emotion_snapshot TEXT        NULL,
    assessment_file_name VARCHAR(256) NULL,
    gmt_created          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_learning_session_user_id (user_id),
    CONSTRAINT fk_learning_session_user
        FOREIGN KEY (user_id) REFERENCES app_user (id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- diagnostic_report
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS diagnostic_report (
    id                      BIGINT       NOT NULL AUTO_INCREMENT,
    learning_session_id     BIGINT       NOT NULL,
    diagnosis_id            VARCHAR(64)  NULL,
    reasoning_trace         TEXT         NULL,
    rag_application_context TEXT         NULL,
    rag_algorithm_context   TEXT         NULL,
    rag_math_context        TEXT         NULL,
    gmt_created             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_diagnostic_report_session_id (learning_session_id),
    CONSTRAINT fk_diagnostic_report_session
        FOREIGN KEY (learning_session_id) REFERENCES learning_session (id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- diagnostic_weak_node (weak knowledge nodes for frontend DiagnosticReport)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS diagnostic_weak_node (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    diagnostic_report_id BIGINT       NOT NULL,
    node_name            VARCHAR(128) NOT NULL,
    knowledge_layer      VARCHAR(16)  NOT NULL COMMENT 'APPLICATION | ALGORITHM | MATH',
    mastery_score        INT          NOT NULL COMMENT '0-100',
    gmt_created          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_weak_node_report_id (diagnostic_report_id),
    CONSTRAINT fk_weak_node_report
        FOREIGN KEY (diagnostic_report_id) REFERENCES diagnostic_report (id)
        ON DELETE CASCADE,
    CONSTRAINT chk_weak_node_mastery CHECK (mastery_score BETWEEN 0 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- user_log (existing audit / event table)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_log (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    user_id        BIGINT      NULL,
    learning_phase VARCHAR(64) NULL,
    event_type     VARCHAR(64) NULL,
    payload        TEXT        NULL,
    gmt_created    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user_log_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- user_feedback
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_feedback (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    user_id       BIGINT        NOT NULL,
    category      VARCHAR(32)   NOT NULL,
    message       VARCHAR(2000) NOT NULL,
    contact       VARCHAR(128)  NULL,
    page_path     VARCHAR(256)  NULL,
    status        VARCHAR(24)   NOT NULL DEFAULT 'PENDING',
    gmt_created   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user_feedback_user_created (user_id, gmt_created),
    KEY idx_user_feedback_status_created (status, gmt_created),
    CONSTRAINT fk_user_feedback_user
        FOREIGN KEY (user_id) REFERENCES app_user (id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
