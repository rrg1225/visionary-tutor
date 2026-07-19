ALTER TABLE session_chat_message
    ADD COLUMN context_type VARCHAR(32) NOT NULL DEFAULT 'GENERAL' AFTER role,
    ADD COLUMN context_key VARCHAR(160) NOT NULL DEFAULT '' AFTER context_type,
    ADD COLUMN context_title VARCHAR(255) NULL AFTER context_key,
    ADD COLUMN metadata_json LONGTEXT NULL AFTER content;

CREATE INDEX idx_session_chat_scope
    ON session_chat_message (learning_session_id, context_type, context_key, seq);
