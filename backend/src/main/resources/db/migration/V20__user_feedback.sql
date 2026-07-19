-- Persist learner-submitted UX, content and product feedback.

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

