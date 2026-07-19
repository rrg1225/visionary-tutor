DELIMITER //

CREATE PROCEDURE add_generated_artifact_media_columns()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'generated_artifact'
          AND COLUMN_NAME = 'media_task_id'
    ) THEN
        ALTER TABLE generated_artifact
            ADD COLUMN media_task_id VARCHAR(128) NULL AFTER progress;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'generated_artifact'
          AND COLUMN_NAME = 'media_status'
    ) THEN
        ALTER TABLE generated_artifact
            ADD COLUMN media_status VARCHAR(32) NULL AFTER media_task_id;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'generated_artifact'
          AND COLUMN_NAME = 'media_url'
    ) THEN
        ALTER TABLE generated_artifact
            ADD COLUMN media_url TEXT NULL AFTER media_status;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'generated_artifact'
          AND COLUMN_NAME = 'cover_image_url'
    ) THEN
        ALTER TABLE generated_artifact
            ADD COLUMN cover_image_url TEXT NULL AFTER media_url;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'generated_artifact'
          AND COLUMN_NAME = 'media_error'
    ) THEN
        ALTER TABLE generated_artifact
            ADD COLUMN media_error TEXT NULL AFTER cover_image_url;
    END IF;
END //

DELIMITER ;

CALL add_generated_artifact_media_columns();
DROP PROCEDURE add_generated_artifact_media_columns;
