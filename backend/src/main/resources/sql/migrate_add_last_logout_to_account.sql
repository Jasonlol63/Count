-- Adds `account.last_logout`: records when a Member-tab account (`account` table) last called
-- POST /auth/logout. Pairs with the existing `last_login` column, shown as a new "Last Logout"
-- column on the Account list — mirrors migrate_add_last_logout_to_user.sql for Admin accounts.
--
-- Coverage note: only a successful call to /auth/logout sets this column. A closed browser
-- tab or an expired token without an explicit logout leaves it unchanged.
--
-- Safe to re-run: uses an information_schema check before ADD COLUMN.
-- Example: mysql -u root count_real < backend/src/main/resources/sql/migrate_add_last_logout_to_account.sql

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'account' AND COLUMN_NAME = 'last_logout'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE `account` ADD COLUMN `last_logout` DATETIME DEFAULT NULL AFTER `last_login`',
    'SELECT ''last_logout already present, skipping''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
