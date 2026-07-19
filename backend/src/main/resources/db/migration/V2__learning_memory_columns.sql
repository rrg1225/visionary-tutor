-- Adds persisted conversation memory fields used by the four-layer memory prompt.

DELIMITER //

CREATE PROCEDURE add_learning_memory_columns()
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'learning_session'
          AND COLUMN_NAME = 'conversation_summary'
    ) THEN
        ALTER TABLE learning_session
            ADD COLUMN conversation_summary TEXT NULL AFTER streaming_handout;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'learning_session'
          AND COLUMN_NAME = 'last_emotion_snapshot'
    ) THEN
        ALTER TABLE learning_session
            ADD COLUMN last_emotion_snapshot TEXT NULL AFTER conversation_summary;
    END IF;
END//

DELIMITER ;

CALL add_learning_memory_columns();

DROP PROCEDURE add_learning_memory_columns;
