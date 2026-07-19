DELIMITER //

CREATE PROCEDURE add_generated_artifact_poll_retry_count()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'generated_artifact'
          AND COLUMN_NAME = 'poll_retry_count'
    ) THEN
        ALTER TABLE generated_artifact
            ADD COLUMN poll_retry_count INT NULL DEFAULT 0 AFTER media_error;
    END IF;
END //

DELIMITER ;

CALL add_generated_artifact_poll_retry_count();
DROP PROCEDURE add_generated_artifact_poll_retry_count;
