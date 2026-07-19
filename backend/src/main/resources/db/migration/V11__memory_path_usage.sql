-- Memory management, learning path step progress, resource usage, and learning_metrics baseline.

CREATE TABLE IF NOT EXISTS user_memory (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    learning_session_id BIGINT NULL,
    memory_type VARCHAR(32) NOT NULL,
    memory_key VARCHAR(128) NOT NULL,
    memory_value TEXT NOT NULL,
    source_type VARCHAR(32) NOT NULL DEFAULT 'system',
    source_id BIGINT NULL,
    priority INT NOT NULL DEFAULT 50,
    confidence_score DECIMAL(5, 2) NULL,
    review_status VARCHAR(32) NOT NULL DEFAULT 'pending',
    is_active TINYINT(1) NOT NULL DEFAULT 1,
    gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user_memory_user (user_id),
    KEY idx_user_memory_session (learning_session_id),
    KEY idx_user_memory_review (user_id, review_status),
    CONSTRAINT fk_user_memory_user
        FOREIGN KEY (user_id) REFERENCES app_user (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_user_memory_session
        FOREIGN KEY (learning_session_id) REFERENCES learning_session (id)
        ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS memory_update_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    learning_session_id BIGINT NULL,
    memory_id BIGINT NULL,
    old_value TEXT NULL,
    new_value TEXT NOT NULL,
    update_reason VARCHAR(512) NULL,
    source_text TEXT NULL,
    agent_score DECIMAL(5, 2) NULL,
    update_status VARCHAR(32) NOT NULL DEFAULT 'success',
    gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_memory_update_log_user (user_id),
    CONSTRAINT fk_memory_update_log_user
        FOREIGN KEY (user_id) REFERENCES app_user (id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS learning_path_step (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    learning_session_id BIGINT NOT NULL,
    path_node_id BIGINT NULL,
    artifact_id BIGINT NULL,
    step_order INT NOT NULL,
    step_title VARCHAR(255) NOT NULL,
    step_goal TEXT NULL,
    recommended_resource_ids TEXT NULL,
    estimated_minutes INT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'not_started',
    started_at DATETIME NULL,
    completed_at DATETIME NULL,
    time_spent_seconds INT NULL,
    gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_path_step_user_session_order (user_id, learning_session_id, step_order),
    KEY idx_path_step_session (learning_session_id),
    CONSTRAINT fk_path_step_user
        FOREIGN KEY (user_id) REFERENCES app_user (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_path_step_session
        FOREIGN KEY (learning_session_id) REFERENCES learning_session (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_path_step_node
        FOREIGN KEY (path_node_id) REFERENCES learning_path_node (id)
        ON DELETE SET NULL,
    CONSTRAINT fk_path_step_artifact
        FOREIGN KEY (artifact_id) REFERENCES generated_artifact (id)
        ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS resource_usage_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    learning_session_id BIGINT NULL,
    resource_id BIGINT NULL,
    action_type VARCHAR(32) NOT NULL,
    duration_seconds INT NULL,
    feedback TEXT NULL,
    gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_resource_usage_user (user_id),
    KEY idx_resource_usage_resource (resource_id),
    CONSTRAINT fk_resource_usage_user
        FOREIGN KEY (user_id) REFERENCES app_user (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_resource_usage_session
        FOREIGN KEY (learning_session_id) REFERENCES learning_session (id)
        ON DELETE SET NULL,
    CONSTRAINT fk_resource_usage_resource
        FOREIGN KEY (resource_id) REFERENCES generated_artifact (id)
        ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS learning_metrics (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    learning_session_id BIGINT NULL,
    metric_type VARCHAR(32) NOT NULL,
    concept VARCHAR(128) NULL,
    value_numeric DOUBLE NULL,
    value_text TEXT NULL,
    before_value DOUBLE NULL,
    after_value DOUBLE NULL,
    event_time DATETIME(6) NOT NULL,
    source VARCHAR(64) NULL,
    gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_learning_metrics_user (user_id),
    KEY idx_learning_metrics_session (learning_session_id),
    CONSTRAINT fk_learning_metrics_user
        FOREIGN KEY (user_id) REFERENCES app_user (id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
