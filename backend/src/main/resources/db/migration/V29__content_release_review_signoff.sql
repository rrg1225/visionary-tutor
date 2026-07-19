CREATE TABLE content_release_review (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    content_type VARCHAR(32) NOT NULL,
    content_version VARCHAR(64) NOT NULL,
    reviewer_id BIGINT NOT NULL,
    reviewer_role VARCHAR(32) NOT NULL,
    decision VARCHAR(16) NOT NULL,
    notes VARCHAR(2000) NOT NULL,
    reviewed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_release_reviewer (content_type, content_version, reviewer_id),
    INDEX idx_release_status (content_type, content_version, decision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
