CREATE TABLE IF NOT EXISTS fixed_exam_attempt (
    id                       BIGINT         NOT NULL AUTO_INCREMENT,
    user_id                  BIGINT         NOT NULL,
    learning_session_id      BIGINT         NULL,
    paper_code               VARCHAR(64)    NOT NULL,
    catalog_version          VARCHAR(32)    NOT NULL,
    status                   VARCHAR(24)    NOT NULL DEFAULT 'IN_PROGRESS',
    started_at               DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    submitted_at             DATETIME       NULL,
    total_score              DECIMAL(8,2)   NULL,
    max_score                DECIMAL(8,2)   NULL,
    total_duration_seconds   INT            NOT NULL DEFAULT 0,
    gmt_created              DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified             DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_fixed_exam_attempt_user (user_id, gmt_modified),
    KEY idx_fixed_exam_attempt_paper (paper_code, catalog_version),
    KEY idx_fixed_exam_attempt_session (learning_session_id),
    CONSTRAINT fk_fixed_exam_attempt_user
        FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_fixed_exam_attempt_session
        FOREIGN KEY (learning_session_id) REFERENCES learning_session (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS fixed_exam_answer (
    id                              BIGINT         NOT NULL AUTO_INCREMENT,
    attempt_id                      BIGINT         NOT NULL,
    question_id                     VARCHAR(96)    NOT NULL,
    user_answer                     LONGTEXT       NULL,
    score                           DECIMAL(8,2)   NULL,
    max_score                       DECIMAL(8,2)   NOT NULL,
    is_correct                      TINYINT(1)     NOT NULL DEFAULT 0,
    is_draft                        TINYINT(1)     NOT NULL DEFAULT 1,
    viewed_answer_before_submit     TINYINT(1)     NOT NULL DEFAULT 0,
    duration_seconds                INT            NOT NULL DEFAULT 0,
    revision_count                  INT            NOT NULL DEFAULT 0,
    grading_json                    LONGTEXT       NULL,
    gmt_created                     DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified                    DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fixed_exam_answer_question (attempt_id, question_id),
    KEY idx_fixed_exam_answer_attempt (attempt_id),
    CONSTRAINT fk_fixed_exam_answer_attempt
        FOREIGN KEY (attempt_id) REFERENCES fixed_exam_attempt (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS fixed_exam_report (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    attempt_id            BIGINT       NOT NULL,
    user_id               BIGINT       NOT NULL,
    learning_session_id   BIGINT       NULL,
    paper_code            VARCHAR(64)  NOT NULL,
    report_json           LONGTEXT     NOT NULL,
    gmt_created           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fixed_exam_report_attempt (attempt_id),
    KEY idx_fixed_exam_report_user (user_id, gmt_created),
    KEY idx_fixed_exam_report_session (learning_session_id),
    CONSTRAINT fk_fixed_exam_report_attempt
        FOREIGN KEY (attempt_id) REFERENCES fixed_exam_attempt (id) ON DELETE CASCADE,
    CONSTRAINT fk_fixed_exam_report_user
        FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_fixed_exam_report_session
        FOREIGN KEY (learning_session_id) REFERENCES learning_session (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE question_attempt
    ADD COLUMN source_type VARCHAR(32) NULL AFTER learning_session_id,
    ADD COLUMN source_question_id VARCHAR(96) NULL AFTER source_type,
    ADD COLUMN fixed_exam_attempt_id BIGINT NULL AFTER source_question_id,
    ADD COLUMN viewed_answer_before_submit TINYINT(1) NOT NULL DEFAULT 0 AFTER skipped,
    ADD COLUMN duration_seconds INT NOT NULL DEFAULT 0 AFTER viewed_answer_before_submit;

CREATE INDEX idx_question_attempt_fixed_exam ON question_attempt (fixed_exam_attempt_id);
