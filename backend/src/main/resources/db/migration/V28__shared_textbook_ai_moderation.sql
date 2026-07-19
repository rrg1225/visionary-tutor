ALTER TABLE shared_textbook
    ADD COLUMN ai_review_status VARCHAR(32) NOT NULL DEFAULT 'not_scanned' AFTER review_status,
    ADD COLUMN ai_risk_level VARCHAR(16) NULL AFTER ai_review_status,
    ADD COLUMN ai_review_reason VARCHAR(1000) NULL AFTER ai_risk_level,
    ADD COLUMN ai_reviewed_at DATETIME NULL AFTER ai_review_reason;

CREATE INDEX idx_shared_textbook_ai_review ON shared_textbook (ai_review_status, review_status);
