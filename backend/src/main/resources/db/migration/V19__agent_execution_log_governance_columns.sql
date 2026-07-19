ALTER TABLE agent_execution_log
    ADD COLUMN artifact_type VARCHAR(64) NULL AFTER status,
    ADD COLUMN fallback_reason TEXT NULL AFTER artifact_type,
    ADD COLUMN revision_round INT NOT NULL DEFAULT 0 AFTER fallback_reason,
    ADD COLUMN max_revision_rounds INT NOT NULL DEFAULT 2 AFTER revision_round,
    ADD COLUMN revision_status VARCHAR(32) NULL AFTER max_revision_rounds,
    ADD COLUMN reflection_reason TEXT NULL AFTER revision_status,
    ADD COLUMN critic_verdict VARCHAR(32) NULL AFTER reflection_reason,
    ADD COLUMN factuality_score DOUBLE NULL AFTER critic_verdict;
