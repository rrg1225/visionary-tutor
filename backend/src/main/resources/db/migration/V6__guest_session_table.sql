CREATE TABLE IF NOT EXISTS guest_session (
    guest_id VARCHAR(64) NOT NULL,
    context_json LONGTEXT NULL,
    expires_at DATETIME NOT NULL,
    converted_user_id BIGINT NULL,
    device_fingerprint VARCHAR(256) NULL,
    ip_address VARCHAR(64) NULL,
    gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (guest_id),
    KEY idx_converted_user_id (converted_user_id),
    KEY idx_expires_at (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
