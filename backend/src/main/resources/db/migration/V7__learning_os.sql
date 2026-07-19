DELIMITER //

CREATE PROCEDURE apply_learning_os_schema()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'app_user' AND COLUMN_NAME = 'profile_version'
    ) THEN
        ALTER TABLE app_user
            ADD COLUMN profile_version INT NOT NULL DEFAULT 1 AFTER learner_profile_snapshot,
            ADD COLUMN path_version INT NOT NULL DEFAULT 1 AFTER profile_version,
            ADD COLUMN last_policy_reason VARCHAR(512) NULL AFTER path_version;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'generated_artifact' AND COLUMN_NAME = 'publish_status'
    ) THEN
        ALTER TABLE generated_artifact
            ADD COLUMN publish_status VARCHAR(32) NOT NULL DEFAULT 'PUBLISHED' AFTER validation_status,
            ADD COLUMN verification_audit_json TEXT NULL AFTER publish_status;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.TABLES
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'learning_os_event'
    ) THEN
        CREATE TABLE learning_os_event (
            id BIGINT AUTO_INCREMENT PRIMARY KEY,
            user_id BIGINT NULL,
            learning_session_id BIGINT NULL,
            event_type VARCHAR(64) NOT NULL,
            payload_json LONGTEXT NULL,
            policy_reason VARCHAR(512) NULL,
            gmt_created TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
            INDEX idx_learning_os_event_user (user_id),
            INDEX idx_learning_os_event_session (learning_session_id)
        );
    END IF;
END //

DELIMITER ;

CALL apply_learning_os_schema();
DROP PROCEDURE apply_learning_os_schema;

UPDATE generated_artifact
SET publish_status = 'PUBLISHED'
WHERE publish_status IS NULL OR publish_status = '';

UPDATE generated_artifact
SET publish_status = 'DEGRADED'
WHERE validation_status IN ('NO_EVIDENCE', 'WEAK_GROUNDING', 'AUTO_PUSH');
