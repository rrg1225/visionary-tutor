CREATE TABLE IF NOT EXISTS question_attempt (
    id                   BIGINT        NOT NULL AUTO_INCREMENT,
    user_id              BIGINT        NOT NULL,
    learning_session_id  BIGINT        NULL,
    question_key         VARCHAR(96)   NOT NULL,
    prompt               TEXT          NOT NULL,
    user_answer          TEXT          NULL,
    correct_answer       TEXT          NULL,
    explanation          TEXT          NULL,
    concept              VARCHAR(128)  NULL,
    is_correct           TINYINT(1)    NOT NULL DEFAULT 0,
    skipped              TINYINT(1)    NOT NULL DEFAULT 0,
    review_status        VARCHAR(24)   NOT NULL DEFAULT 'DUE',
    review_count         INT           NOT NULL DEFAULT 0,
    next_review_at       DATETIME      NULL,
    last_reviewed_at     DATETIME      NULL,
    gmt_created          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_question_attempt_user_wrong (user_id, is_correct, gmt_created),
    KEY idx_question_attempt_due (user_id, review_status, next_review_at),
    KEY idx_question_attempt_session (learning_session_id),
    CONSTRAINT fk_question_attempt_user
        FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_question_attempt_session
        FOREIGN KEY (learning_session_id) REFERENCES learning_session (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
