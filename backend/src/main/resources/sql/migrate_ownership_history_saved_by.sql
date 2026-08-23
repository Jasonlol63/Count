-- Migrate tenant_ownership_history.saved_by (FK -> user.id) to a login_id VARCHAR.
-- Root cause: saveOwnership() only allows role="owner" to save, and Owner sessions carry
-- owner.id (owner table) in SessionUser.user_id, not user.id (user/admin table). The two
-- tables have independent auto_increment id spaces, so the FK to user(id) failed whenever
-- the logged-in owner's id didn't coincidentally also exist in `user`.
-- Same class of bug already fixed once for process_submitted.user_id (see
-- migrate_process_submitted_actor.sql) and aligns with the created_by/updated_by login_id
-- convention used everywhere else in schema.sql (see TABLE_MIGRATION.md §1): "created_by /
-- updated_by 存字符串 login，不强制存 user 数字 id". A plain VARCHAR login_id is agnostic to
-- which identity table (owner/user) the actor came from, so it also covers the case where a
-- non-owner (e.g. a partner-role `user` account granted the OWNERSHIP module permission)
-- saves ownership in the future.

SET @has_saved_by_int := (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tenant_ownership_history'
    AND COLUMN_NAME = 'saved_by' AND DATA_TYPE IN ('int', 'smallint', 'mediumint', 'bigint', 'tinyint')
);

-- Drop the FK first (name may differ if MySQL auto-generated it); ignore failure.
SET @fk_name := (
  SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tenant_ownership_history'
    AND COLUMN_NAME = 'saved_by' AND REFERENCED_TABLE_NAME = 'user'
  LIMIT 1
);
SET @sql := IF(@fk_name IS NOT NULL,
  CONCAT('ALTER TABLE `tenant_ownership_history` DROP FOREIGN KEY `', @fk_name, '`'),
  'SELECT ''no fk_toh_saved_by to drop'' AS info');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Add a temp string column, backfill from whichever identity table the old numeric id
-- actually matches (best-effort; historical rows predate the type split), then swap in.
SET @sql := IF(@has_saved_by_int > 0,
  'ALTER TABLE `tenant_ownership_history` ADD COLUMN `saved_by_login` VARCHAR(50) DEFAULT NULL AFTER `saved_by`',
  'SELECT ''saved_by already migrated'' AS info');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(@has_saved_by_int > 0,
  'UPDATE `tenant_ownership_history` h
     JOIN `user` u ON u.id = h.saved_by
     SET h.saved_by_login = u.login_id',
  'SELECT ''skip user backfill'' AS info');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(@has_saved_by_int > 0,
  'UPDATE `tenant_ownership_history` h
     JOIN `owner` o ON o.id = h.saved_by
     SET h.saved_by_login = o.owner_code
   WHERE h.saved_by_login IS NULL',
  'SELECT ''skip owner backfill'' AS info');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(@has_saved_by_int > 0,
  'ALTER TABLE `tenant_ownership_history` DROP COLUMN `saved_by`',
  'SELECT ''skip drop old saved_by'' AS info');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(@has_saved_by_int > 0,
  'ALTER TABLE `tenant_ownership_history`
     CHANGE COLUMN `saved_by_login` `saved_by` VARCHAR(50) DEFAULT NULL
       COMMENT ''操作人 login_id（admin=user.login_id；owner=owner_code）''',
  'SELECT ''skip rename saved_by_login'' AS info');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
