CREATE TABLE IF NOT EXISTS generated_artifact (
    id BIGINT NOT NULL AUTO_INCREMENT,
    learning_session_id BIGINT NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    artifact_type VARCHAR(32) NOT NULL,
    title VARCHAR(128) NOT NULL,
    content_markdown LONGTEXT NULL,
    content_json LONGTEXT NULL,
    citations_json TEXT NULL,
    validation_status VARCHAR(32) NOT NULL DEFAULT 'UNVERIFIED',
    review_notes TEXT NULL,
    progress INT NULL DEFAULT 100,
    gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_generated_artifact_session (learning_session_id),
    KEY idx_generated_artifact_run (run_id),
    CONSTRAINT fk_generated_artifact_session
        FOREIGN KEY (learning_session_id) REFERENCES learning_session (id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS agent_run_step (
    id BIGINT NOT NULL AUTO_INCREMENT,
    run_id VARCHAR(64) NOT NULL,
    learning_session_id BIGINT NOT NULL,
    agent_name VARCHAR(64) NOT NULL,
    step_order INT NOT NULL,
    input_summary TEXT NULL,
    output_summary TEXT NULL,
    critique TEXT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'COMPLETED',
    gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_agent_run_step_run (run_id),
    KEY idx_agent_run_step_session (learning_session_id),
    CONSTRAINT fk_agent_run_step_session
        FOREIGN KEY (learning_session_id) REFERENCES learning_session (id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
