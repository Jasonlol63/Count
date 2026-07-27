-- Add process.category (GAME | BANK) without losing existing rows.
-- Existing rows receive DEFAULT 'GAME' — same behavior as legacy Games process list.
-- Safe to re-run (checks INFORMATION_SCHEMA before each step).
-- Target: MariaDB 10.4+ / MySQL 8+

-- 1) Add category column (after tenant_id if present, else after id)
SET @has_category := (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'process'
    AND COLUMN_NAME = 'category'
);

SET @after_col := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'process' AND COLUMN_NAME = 'tenant_id') > 0,
  'tenant_id',
  'id'
);

SET @sql := IF(
  @has_category = 0,
  CONCAT(
    'ALTER TABLE `process` ',
    'ADD COLUMN `category` ENUM(''GAME'', ''BANK'') NOT NULL DEFAULT ''GAME'' ',
    'COMMENT ''GAME=process_day+submitted filter; BANK=fixed four codes'' ',
    'AFTER `', @after_col, '`'
  ),
  'SELECT ''process.category already exists'' AS info'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2) Backfill (only if column was added nullable by mistake in a partial run)
UPDATE `process`
SET `category` = 'GAME'
WHERE `category` IS NULL OR TRIM(`category`) = '';

-- 3) Drop legacy unique key (tenant_id + code) so we can add (tenant_id, category, code)
--    Adjust INDEX_NAME if your DB used a different name.
SET @drop_uk := NULL;

SELECT INDEX_NAME INTO @drop_uk
FROM INFORMATION_SCHEMA.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'process'
  AND NON_UNIQUE = 0
  AND INDEX_NAME <> 'PRIMARY'
  AND INDEX_NAME NOT IN ('uk_process_tenant_category_code')
GROUP BY INDEX_NAME
HAVING SUM(CASE WHEN COLUMN_NAME = 'tenant_id' THEN 1 ELSE 0 END) = 1
   AND SUM(CASE WHEN COLUMN_NAME = 'code' THEN 1 ELSE 0 END) = 1
   AND SUM(CASE WHEN COLUMN_NAME = 'category' THEN 1 ELSE 0 END) = 0
LIMIT 1;

SET @sql := IF(
  @drop_uk IS NOT NULL,
  CONCAT('ALTER TABLE `process` DROP INDEX `', @drop_uk, '`'),
  'SELECT ''no legacy (tenant_id,code) unique key to drop'' AS info'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 4) New unique: same tenant may have GAME WM and BANK WM as different rows
SET @has_new_uk := (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'process'
    AND INDEX_NAME = 'uk_process_tenant_category_code'
);

SET @sql := IF(
  @has_new_uk = 0,
  'ALTER TABLE `process`
     ADD UNIQUE KEY `uk_process_tenant_category_code` (`tenant_id`, `category`, `code`)',
  'SELECT ''uk_process_tenant_category_code already exists'' AS info'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 5) List/filter index for Data Capture + Process list
SET @has_cat_idx := (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'process'
    AND INDEX_NAME = 'idx_process_tenant_category'
);

SET @sql := IF(
  @has_cat_idx = 0,
  'ALTER TABLE `process`
     ADD KEY `idx_process_tenant_category` (`tenant_id`, `category`, `status`)',
  'SELECT ''idx_process_tenant_category already exists'' AS info'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 6) Optional: mark fixed Bank Data Capture codes as BANK (run only when you seed/use BANK category)
-- UPDATE `process`
-- SET `category` = 'BANK'
-- WHERE UPPER(TRIM(`code`)) IN ('PROFIT', 'SALARY', 'COMMISSION', 'BONUS');

ALTER TABLE `process`
  COMMENT = '流程配置表（category=GAME|BANK；GAME/BANK 同表不同规则）';
