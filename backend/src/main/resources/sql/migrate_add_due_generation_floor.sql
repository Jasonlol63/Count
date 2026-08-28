-- Adds `bank_process.due_generation_floor`, an optional override for where Accounting Due
-- backfill generation starts (see BankAccountingDueServiceImpl#creationMonthFloor).
--
-- Background: due generation walks forward from bank_process.created_at (or day_start) to
-- today, re-surfacing any month in between that was never POSTED/SKIPPED. For records whose
-- created_at reflects a migration/import timestamp rather than the real contract start, this
-- causes stale past-month dues to reappear in the Inbox on every load. created_at itself is
-- left untouched (it still means "row created at") -- this column is a separate, purely
-- optional generation-floor override, NULL by default so existing behavior is unchanged.
--
-- Safe to re-run: uses information_schema checks before ADD COLUMN.
-- Example: mysql -u root count_real < backend/src/main/resources/sql/migrate_add_due_generation_floor.sql

-- 1. Add the column if missing.
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bank_process' AND COLUMN_NAME = 'due_generation_floor'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE `bank_process` ADD COLUMN `due_generation_floor` DATE DEFAULT NULL COMMENT ''Optional override for the due-backfill floor month; when set, Inbox generation starts here instead of created_at'' AFTER `expired_at_creation`',
    'SELECT ''due_generation_floor already present, skipping''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. One-off data fix: stop these still-in-contract records (created_at set by migration/import,
--    not the real contract start) from regenerating past-month dues. Only touches records that are
--    still within their contract period (day_end in the future) -- expired record-only contracts
--    (day_end already passed) are intentionally left untouched.
UPDATE `bank_process`
SET `due_generation_floor` = CURDATE()
WHERE `id` IN (189, 482, 527, 530, 581, 584, 593, 601, 602, 603);
