CREATE TABLE IF NOT EXISTS learning_path_node (
    id BIGINT NOT NULL AUTO_INCREMENT,
    artifact_id BIGINT NOT NULL,
    learning_session_id BIGINT NOT NULL,
    node_key VARCHAR(96) NOT NULL,
    label VARCHAR(255) NOT NULL,
    resource_type VARCHAR(32) NULL,
    mastery INT NULL,
    estimated_minutes INT NULL,
    order_index INT NOT NULL,
    metadata_json TEXT NULL,
    gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_learning_path_node_artifact_key (artifact_id, node_key),
    KEY idx_learning_path_node_session (learning_session_id),
    CONSTRAINT fk_learning_path_node_artifact
        FOREIGN KEY (artifact_id) REFERENCES generated_artifact (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_learning_path_node_session
        FOREIGN KEY (learning_session_id) REFERENCES learning_session (id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS learning_path_edge (
    id BIGINT NOT NULL AUTO_INCREMENT,
    artifact_id BIGINT NOT NULL,
    learning_session_id BIGINT NOT NULL,
    from_node_key VARCHAR(96) NOT NULL,
    to_node_key VARCHAR(96) NOT NULL,
    relation_type VARCHAR(32) NOT NULL DEFAULT 'PREREQUISITE',
    label VARCHAR(255) NULL,
    order_index INT NOT NULL,
    gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_learning_path_edge_artifact_pair (artifact_id, from_node_key, to_node_key),
    KEY idx_learning_path_edge_session (learning_session_id),
    CONSTRAINT fk_learning_path_edge_artifact
        FOREIGN KEY (artifact_id) REFERENCES generated_artifact (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_learning_path_edge_session
        FOREIGN KEY (learning_session_id) REFERENCES learning_session (id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
