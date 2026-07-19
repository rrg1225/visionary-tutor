CREATE TABLE IF NOT EXISTS agent_revision_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    artifact_id BIGINT NOT NULL,
    revision_round INT NOT NULL,
    composite_score DOUBLE NOT NULL,
    score_delta DOUBLE NOT NULL,
    breaker_decision VARCHAR(32) NOT NULL,
    critic_feedback TEXT,
    gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_agent_revision_log_artifact (artifact_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 返修审计日志表';
