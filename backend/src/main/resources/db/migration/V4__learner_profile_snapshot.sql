DELIMITER //

CREATE PROCEDURE add_learner_profile_snapshot()
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'app_user'
          AND COLUMN_NAME = 'learner_profile_snapshot'
    ) THEN
        ALTER TABLE app_user
            ADD COLUMN learner_profile_snapshot LONGTEXT NULL AFTER learning_goal;
    END IF;
END //

DELIMITER ;

CALL add_learner_profile_snapshot();
DROP PROCEDURE add_learner_profile_snapshot;
