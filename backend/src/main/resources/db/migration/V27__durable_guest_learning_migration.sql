ALTER TABLE guest_session
    ADD COLUMN migration_status VARCHAR(24) NOT NULL DEFAULT 'PENDING' AFTER converted_user_id,
    ADD COLUMN migration_error VARCHAR(512) NULL AFTER migration_status,
    ADD COLUMN migrated_at DATETIME NULL AFTER migration_error,
    ADD COLUMN migrated_session_id BIGINT NULL AFTER migrated_at;

CREATE INDEX idx_guest_migration_status
    ON guest_session (migration_status, expires_at);
