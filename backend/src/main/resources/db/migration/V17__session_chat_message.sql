-- Persisted chat messages per learning session (Plan B chat history).

CREATE TABLE IF NOT EXISTS session_chat_message (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    learning_session_id  BIGINT       NOT NULL,
    user_id              BIGINT       NOT NULL,
    role                 VARCHAR(16)  NOT NULL,
    content              LONGTEXT     NOT NULL,
    seq                  INT          NOT NULL,
    gmt_created          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_session_chat_seq (learning_session_id, seq),
    KEY idx_session_chat_session (learning_session_id),
    KEY idx_session_chat_user (user_id),
    CONSTRAINT fk_session_chat_session
        FOREIGN KEY (learning_session_id) REFERENCES learning_session (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_session_chat_user
        FOREIGN KEY (user_id) REFERENCES app_user (id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
