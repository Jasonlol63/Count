-- Create BANK Data Capture draft tables if missing.
-- Example: Get-Content ...\migrate_datacapture_draft.sql | mysql -u root testcount

USE testcount;

CREATE TABLE IF NOT EXISTS `data_capture_draft` (
    `id`            INT UNSIGNED NOT NULL AUTO_INCREMENT,
    `tenant_id`     INT UNSIGNED NOT NULL COMMENT 'FK tenant.id（company/group ledger 均用 tenant）',
    `process_id`    INT UNSIGNED NOT NULL COMMENT 'FK process.id（BANK 四码之一）',
    `currency_id`   INT UNSIGNED NOT NULL COMMENT 'FK currency.id',
    `updated_at`    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `created_at`    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_draft_tenant_process_currency` (`tenant_id`, `process_id`, `currency_id`),
    KEY `idx_draft_tenant` (`tenant_id`),
    CONSTRAINT `fk_draft_tenant` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_draft_process` FOREIGN KEY (`process_id`) REFERENCES `process` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_draft_currency` FOREIGN KEY (`currency_id`) REFERENCES `currency` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='BANK Data Capture 表格草稿头（仅 TEXT）';

CREATE TABLE IF NOT EXISTS `data_capture_draft_cell` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `draft_id`    INT UNSIGNED NOT NULL COMMENT 'FK data_capture_draft.id',
    `row_index`   SMALLINT UNSIGNED NOT NULL COMMENT '0-based（A=0）',
    `col_index`   SMALLINT UNSIGNED NOT NULL COMMENT '1-based（与 UI 列号一致）',
    `cell_value`  TEXT NOT NULL COMMENT '纯文本；空单元格不落库',
    `updated_at`  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_draft_cell_pos` (`draft_id`, `row_index`, `col_index`),
    KEY `idx_draft_cell_draft` (`draft_id`),
    CONSTRAINT `fk_draft_cell_draft` FOREIGN KEY (`draft_id`) REFERENCES `data_capture_draft` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='BANK Data Capture 草稿单元格（无 JSON）';
