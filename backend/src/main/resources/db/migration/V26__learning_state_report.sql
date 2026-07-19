-- 学习状态辅助报告落库（问题21）：关闭辅助后生成的状态观察报告不再只存浏览器
-- localStorage，注册用户的报告持久化到服务端，供报告中心统一检索与题卷报告交叉参考。
-- 隐私边界不变：只保存本机聚合后的指标（样本数、时长、均值、结论文案），不含任何原始视频帧。
CREATE TABLE IF NOT EXISTS learning_state_report (
    id                     BIGINT        NOT NULL AUTO_INCREMENT,
    user_id                BIGINT        NOT NULL,
    learning_session_id    BIGINT        NULL,
    context_type           VARCHAR(64)   NOT NULL,
    context_key            VARCHAR(191)  NOT NULL,
    context_title          VARCHAR(255)  NULL,
    sample_count           INT           NOT NULL DEFAULT 0,
    duration_seconds       INT           NOT NULL DEFAULT 0,
    aggregate_score        INT           NULL,
    is_sufficient          TINYINT(1)    NOT NULL DEFAULT 0,
    headline               VARCHAR(255)  NOT NULL,
    description            TEXT          NULL,
    markers_json           TEXT          NULL,
    gmt_created            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_learning_state_report_user (user_id, gmt_created),
    KEY idx_learning_state_report_context (user_id, context_type, context_key),
    CONSTRAINT fk_learning_state_report_user
        FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_learning_state_report_session
        FOREIGN KEY (learning_session_id) REFERENCES learning_session (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
