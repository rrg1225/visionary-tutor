-- P2: recommendation audit log, proactive push metadata, shared UGC textbook library

CREATE TABLE IF NOT EXISTS resource_recommendation_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    learning_session_id BIGINT NULL,
    query_text TEXT NULL,
    recommended_ids VARCHAR(512) NULL,
    is_fallback TINYINT(1) NULL DEFAULT 0,
    push_source VARCHAR(32) NULL DEFAULT 'manual',
    push_message VARCHAR(512) NULL,
    consumed TINYINT(1) NOT NULL DEFAULT 0,
    gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_rec_log_user (user_id),
    KEY idx_rec_log_session (learning_session_id),
    KEY idx_rec_log_unconsumed (user_id, consumed, gmt_created),
    CONSTRAINT fk_rec_log_user
        FOREIGN KEY (user_id) REFERENCES app_user (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_rec_log_session
        FOREIGN KEY (learning_session_id) REFERENCES learning_session (id)
        ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS shared_textbook (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_user_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT NULL,
    content_markdown MEDIUMTEXT NOT NULL,
    subject_tag VARCHAR(64) NULL DEFAULT 'computer-vision',
    visibility VARCHAR(32) NOT NULL DEFAULT 'public',
    review_status VARCHAR(32) NOT NULL DEFAULT 'approved',
    view_count INT NOT NULL DEFAULT 0,
    gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_shared_textbook_owner (owner_user_id),
    KEY idx_shared_textbook_public (review_status, visibility),
    CONSTRAINT fk_shared_textbook_owner
        FOREIGN KEY (owner_user_id) REFERENCES app_user (id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
