-- Adds `owner.last_login` and `owner.last_logout`: mirrors the existing `user`/`account`
-- last_login (+ new last_logout) columns so the Owner row shown on the Admin User list
-- (synthetic "shadow row" built from the owner table) can display real login/logout times
-- instead of always "-".
--
-- Coverage note: last_logout is only set by a successful call to /auth/logout. A closed
-- browser tab or an expired token without an explicit logout leaves it unchanged.
--
-- Safe to re-run: uses information_schema checks before each ADD COLUMN.
-- Example: mysql -u root count_real < backend/src/main/resources/sql/migrate_add_last_login_logout_to_owner.sql

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'owner' AND COLUMN_NAME = 'last_login'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE `owner` ADD COLUMN `last_login` DATETIME DEFAULT NULL AFTER `status`',
    'SELECT ''last_login already present, skipping''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'owner' AND COLUMN_NAME = 'last_logout'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE `owner` ADD COLUMN `last_logout` DATETIME DEFAULT NULL AFTER `last_login`',
    'SELECT ''last_logout already present, skipping''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
