-- process.created_by / updated_by: INT user.id → VARCHAR(50) login_id
-- Safe backfill: user.login_id → owner.owner_code → CAST(id AS CHAR) fallback
-- Idempotent note: if columns are already VARCHAR, skip this script.
--
-- Applied on testcount 2026-07-14 (sample: created_by 1 → 'JJ')

-- Drop FKs if present
SET @fk_c := (
  SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'process' AND CONSTRAINT_NAME = 'fk_process_created_by'
  LIMIT 1
);
SET @sql_c := IF(@fk_c IS NOT NULL, 'ALTER TABLE `process` DROP FOREIGN KEY `fk_process_created_by`', 'SELECT 1');
PREPARE stmt_c FROM @sql_c; EXECUTE stmt_c; DEALLOCATE PREPARE stmt_c;

SET @fk_u := (
  SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'process' AND CONSTRAINT_NAME = 'fk_process_updated_by'
  LIMIT 1
);
SET @sql_u := IF(@fk_u IS NOT NULL, 'ALTER TABLE `process` DROP FOREIGN KEY `fk_process_updated_by`', 'SELECT 1');
PREPARE stmt_u FROM @sql_u; EXECUTE stmt_u; DEALLOCATE PREPARE stmt_u;

ALTER TABLE `process`
  CHANGE COLUMN `created_by` `created_by_old` INT UNSIGNED NULL,
  CHANGE COLUMN `updated_by` `updated_by_old` INT UNSIGNED NULL;

ALTER TABLE `process`
  ADD COLUMN `created_by` VARCHAR(50) NULL COMMENT '创建人 login_id（admin=user.login_id；owner=owner_code）' AFTER `status`,
  ADD COLUMN `updated_by` VARCHAR(50) NULL COMMENT '修改人 login_id（同上）' AFTER `created_by`;

UPDATE `process` p
LEFT JOIN `user` u ON u.id = p.created_by_old
LEFT JOIN `owner` o ON o.id = p.created_by_old
SET p.created_by = COALESCE(
  NULLIF(TRIM(u.login_id), ''),
  NULLIF(TRIM(o.owner_code), ''),
  CASE WHEN p.created_by_old IS NULL THEN NULL ELSE CAST(p.created_by_old AS CHAR) END
);

UPDATE `process` p
LEFT JOIN `user` u ON u.id = p.updated_by_old
LEFT JOIN `owner` o ON o.id = p.updated_by_old
SET p.updated_by = COALESCE(
  NULLIF(TRIM(u.login_id), ''),
  NULLIF(TRIM(o.owner_code), ''),
  CASE WHEN p.updated_by_old IS NULL THEN NULL ELSE CAST(p.updated_by_old AS CHAR) END
);

ALTER TABLE `process`
  DROP COLUMN `created_by_old`,
  DROP COLUMN `updated_by_old`;
