ALTER TABLE shared_textbook
    ADD COLUMN source_type VARCHAR(32) NOT NULL DEFAULT 'legacy_import' AFTER subject_tag,
    ADD COLUMN source_title VARCHAR(255) NULL AFTER source_type,
    ADD COLUMN source_url VARCHAR(1000) NULL AFTER source_title,
    ADD COLUMN license_name VARCHAR(128) NULL AFTER source_url,
    ADD COLUMN rights_statement VARCHAR(1000) NULL AFTER license_name,
    ADD COLUMN rights_confirmed BOOLEAN NOT NULL DEFAULT FALSE AFTER rights_statement;

UPDATE shared_textbook
SET source_title = title,
    rights_statement = '历史教材记录：来源信息待管理员复核',
    rights_confirmed = TRUE
WHERE source_title IS NULL;

