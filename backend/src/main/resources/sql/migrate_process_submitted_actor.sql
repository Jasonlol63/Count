-- Migrate process_submitted.user_id (FK -> user.id) to created_by (VARCHAR login_id).
-- Root cause: actor can be admin/owner/member (see SessionUser), but user_id only
-- ever accepted user.id, so submitting as an owner/admin threw a FK violation.
-- Aligns with the created_by(login_id) convention used elsewhere (data_captures, process, ...).

SET @has_user_id := (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'process_submitted' AND COLUMN_NAME = 'user_id'
);

-- Drop the FK first (name may differ if MySQL auto-generated it); ignore failure.
SET @fk_name := (
  SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'process_submitted'
    AND COLUMN_NAME = 'user_id' AND REFERENCED_TABLE_NAME = 'user'
  LIMIT 1
);
SET @sql := IF(@fk_name IS NOT NULL,
  CONCAT('ALTER TABLE `process_submitted` DROP FOREIGN KEY `', @fk_name, '`'),
  'SELECT ''no fk_sp_user to drop'' AS info');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(@has_user_id > 0,
  'ALTER TABLE `process_submitted`
     CHANGE COLUMN `user_id` `created_by` VARCHAR(50) DEFAULT NULL
       COMMENT ''操作人 login_id（admin=user.login_id；owner=owner_code）''',
  'SELECT ''user_id already migrated'' AS info');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
