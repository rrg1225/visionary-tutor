CREATE TABLE IF NOT EXISTS agent_execution_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    student_id VARCHAR(64),
    agent_role VARCHAR(64) NOT NULL,
    thought TEXT,
    action_name VARCHAR(128),
    action_input TEXT,
    observation TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'IN_PROGRESS',
    gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_agent_log_session (session_id),
    INDEX idx_agent_log_student (student_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 执行轨迹日志表';
