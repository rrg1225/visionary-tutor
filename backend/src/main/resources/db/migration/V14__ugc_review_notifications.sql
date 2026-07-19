-- UGC 人工审核字段
ALTER TABLE shared_textbook
    MODIFY review_status VARCHAR(32) NOT NULL DEFAULT 'pending';

ALTER TABLE shared_textbook
    ADD COLUMN reviewed_by BIGINT NULL AFTER review_status,
    ADD COLUMN reviewed_at DATETIME NULL AFTER reviewed_by,
    ADD COLUMN rejection_reason TEXT NULL AFTER reviewed_at;

CREATE INDEX idx_shared_textbook_pending ON shared_textbook (review_status, gmt_created);

-- 用户通知持久化（WebSocket 离线补拉）
CREATE TABLE user_notification (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    notification_type VARCHAR(64) NOT NULL,
    payload_json TEXT NOT NULL,
    is_read TINYINT(1) NOT NULL DEFAULT 0,
    gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_notification_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    INDEX idx_user_notification_unread (user_id, is_read, gmt_created)
);
