-- Dynamic knowledge profiling aggregates (per user × concept).
CREATE TABLE IF NOT EXISTS knowledge_tracing_metrics (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    knowledge_concept VARCHAR(128) NOT NULL,
    total_attempts INT NOT NULL DEFAULT 0,
    correct_count INT NOT NULL DEFAULT 0,
    last_practiced_at DATETIME(6) NULL,
    confidence_score DOUBLE NULL,
    gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_tracing_user_concept (user_id, knowledge_concept),
    KEY idx_knowledge_tracing_user (user_id),
    CONSTRAINT fk_knowledge_tracing_user
        FOREIGN KEY (user_id) REFERENCES app_user (id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
