CREATE TABLE generation_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    trace_id VARCHAR(64) NOT NULL,
    learning_session_id BIGINT NOT NULL,
    from_state VARCHAR(32) NULL,
    to_state VARCHAR(32) NOT NULL,
    agent VARCHAR(64) NULL,
    model VARCHAR(128) NULL,
    prompt_version VARCHAR(64) NULL,
    latency_ms BIGINT NOT NULL DEFAULT 0,
    token_cost BIGINT NULL,
    reason TEXT NULL,
    occurred_at DATETIME(6) NOT NULL,
    INDEX idx_generation_event_trace (trace_id, occurred_at),
    INDEX idx_generation_event_session (learning_session_id, occurred_at)
);
