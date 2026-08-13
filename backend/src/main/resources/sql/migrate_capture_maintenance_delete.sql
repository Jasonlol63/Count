-- Capture Maintenance delete cascade: schema-only, no app logic here.
-- Adds the linking columns needed to cascade a Capture Maintenance delete (always by
-- whole capture_id — never a partial subset of a capture's lines, see docs/maintenance-navigation.md)
-- into transactions and process_submitted, plus the archive table for soft-deleted lines.
-- Safe to re-run (idempotent) on an existing testcount that already has data_capture_line /
-- data_captures / transactions / process_submitted.
-- Example: mysql -u root testcount < backend/src/main/resources/sql/migrate_capture_maintenance_delete.sql

USE testcount;

-- 1) data_capture_line.transaction_id — the WIN/LOSE ledger row this line generated (0 or 1 per line).
SET @has_transaction_id := (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'data_capture_line' AND COLUMN_NAME = 'transaction_id'
);
SET @sql := IF(@has_transaction_id = 0,
  'ALTER TABLE `data_capture_line`
     ADD COLUMN `transaction_id` INT UNSIGNED DEFAULT NULL
       COMMENT ''FK transactions.id — 本行生成的那条 WIN/LOSE 流水（一行至多一条；processed_amount=0 或本字段补加前提交的历史行为 NULL）''
       AFTER `rate_expression`',
  'SELECT ''data_capture_line.transaction_id already exists'' AS info');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_idx_dcl_transaction := (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'data_capture_line' AND INDEX_NAME = 'idx_dcl_transaction'
);
SET @sql := IF(@has_idx_dcl_transaction = 0,
  'ALTER TABLE `data_capture_line` ADD KEY `idx_dcl_transaction` (`transaction_id`)',
  'SELECT ''idx_dcl_transaction already exists'' AS info');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_fk_dcl_transaction := (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'data_capture_line' AND CONSTRAINT_NAME = 'fk_dcl_transaction'
);
SET @sql := IF(@has_fk_dcl_transaction = 0,
  'ALTER TABLE `data_capture_line`
     ADD CONSTRAINT `fk_dcl_transaction`
       FOREIGN KEY (`transaction_id`) REFERENCES `transactions` (`id`) ON DELETE SET NULL',
  'SELECT ''fk_dcl_transaction already exists'' AS info');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2) process_submitted.capture_id — links a submitted marker back to the capture that created it,
-- so deleting that capture (see #3) can remove the marker unconditionally (whole-capture delete
-- always empties it — no "still has live lines" check needed).
SET @has_capture_id := (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'process_submitted' AND COLUMN_NAME = 'capture_id'
);
SET @sql := IF(@has_capture_id = 0,
  'ALTER TABLE `process_submitted`
     ADD COLUMN `capture_id` INT UNSIGNED DEFAULT NULL
       COMMENT ''FK data_captures.id — 产生本条标记的那次提交；Capture Maintenance 按 capture 整体软删时据此清掉标记。历史行可能为 NULL（补加字段前的数据）''
       AFTER `created_at`',
  'SELECT ''process_submitted.capture_id already exists'' AS info');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_idx_sp_capture := (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'process_submitted' AND INDEX_NAME = 'idx_sp_capture'
);
SET @sql := IF(@has_idx_sp_capture = 0,
  'ALTER TABLE `process_submitted` ADD KEY `idx_sp_capture` (`capture_id`)',
  'SELECT ''idx_sp_capture already exists'' AS info');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_fk_sp_capture := (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'process_submitted' AND CONSTRAINT_NAME = 'fk_sp_capture'
);
SET @sql := IF(@has_fk_sp_capture = 0,
  'ALTER TABLE `process_submitted`
     ADD CONSTRAINT `fk_sp_capture`
       FOREIGN KEY (`capture_id`) REFERENCES `data_captures` (`id`) ON DELETE SET NULL',
  'SELECT ''fk_sp_capture already exists'' AS info');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3) data_capture_line_deleted — archive table for Capture Maintenance soft-delete.
-- Delete unit is always a whole capture_id (all its lines together), never a partial subset.
-- data_captures header itself is never removed, even once every line under it is archived.
CREATE TABLE IF NOT EXISTS `data_capture_line_deleted` (
    `id`                    INT UNSIGNED NOT NULL AUTO_INCREMENT,
    `line_id`               INT UNSIGNED NOT NULL COMMENT '原 data_capture_line.id',
    `tenant_id`             INT UNSIGNED NOT NULL COMMENT 'FK tenant.id',
    `capture_id`            INT UNSIGNED NOT NULL COMMENT 'FK data_captures.id（header 不删，仍可查到）',
    `product_type`          ENUM('MAIN', 'SUB') NOT NULL DEFAULT 'MAIN',
    `id_product`            VARCHAR(255) NOT NULL,
    `id_product_main`       VARCHAR(255) DEFAULT NULL,
    `id_product_sub`        VARCHAR(255) DEFAULT NULL,
    `description_main`      VARCHAR(255) DEFAULT NULL,
    `description_sub`       VARCHAR(255) DEFAULT NULL,
    `formula_variant`       TINYINT UNSIGNED NOT NULL DEFAULT 1,
    `display_order`         INT DEFAULT NULL,
    `account_id`            INT UNSIGNED NOT NULL,
    `currency_id`           INT UNSIGNED NOT NULL,
    `source_columns`        TEXT DEFAULT NULL,
    `source_value`          TEXT DEFAULT NULL,
    `source_percent`        VARCHAR(255) NOT NULL DEFAULT '0',
    `enable_source_percent` TINYINT(1) NOT NULL DEFAULT 1,
    `formula`               TEXT DEFAULT NULL,
    `processed_amount`      DECIMAL(25, 8) NOT NULL DEFAULT 0,
    `rate`                  DECIMAL(25, 8) DEFAULT NULL,
    `rate_expression`       VARCHAR(64) DEFAULT NULL,
    `transaction_id`        INT UNSIGNED DEFAULT NULL COMMENT '原关联的 transactions.id（该条流水已单独归档进 transactions_deleted / 硬删，这里仅留痕）',
    `created_at`            TIMESTAMP NULL DEFAULT NULL COMMENT '原 data_capture_line.created_at',
    `deleted_by`            VARCHAR(100) DEFAULT NULL COMMENT '删除人 login_id',
    `deleted_at`            TIMESTAMP NULL DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_dcld_line` (`line_id`),
    KEY `idx_dcld_tenant_capture` (`tenant_id`, `capture_id`),
    KEY `idx_dcld_deleted_at` (`deleted_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Archived soft-deleted data_capture_line rows (Capture Maintenance；按 capture 整体归档)';
