-- Adds `transactions.bank_process_id`: a direct link from a manual transaction to the `bank_process`
-- row it belongs to, independent of `bank_process_posted_id` (which links to a specific periodic
-- `bank_process_accounting_posted` billing period instead).
--
-- Introduced for the "Bank Balance" feature on Add/Edit Process: an optional one-off CONTRA
-- transaction (Customer pays −amount, Supplier receives +amount) representing a small leftover
-- balance that doesn't net to exactly 0.00. At most one such CONTRA row should exist per
-- bank_process at a time (enforced in application logic, not by a DB constraint); the Edit Process
-- form reads it back via this column to show it locked, and a Delete action removes it so a new
-- amount can be entered.
--
-- ON DELETE SET NULL: deleting a bank_process must never cascade-delete or be blocked by a
-- transaction linked to it — the transaction row stays, just loses the link.
--
-- Safe to re-run: uses information_schema checks before ADD COLUMN / ADD KEY / ADD CONSTRAINT.
-- Example: mysql -u root count_real < backend/src/main/resources/sql/migrate_add_bank_process_id_to_transactions.sql

-- 1. Add the column if missing.
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transactions' AND COLUMN_NAME = 'bank_process_id'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE `transactions` ADD COLUMN `bank_process_id` INT UNSIGNED DEFAULT NULL COMMENT ''FK bank_process.id; direct link for one-off transactions tied to the process itself (e.g. Bank Balance), independent of periodic postings (see bank_process_posted_id)'' AFTER `bank_process_posted_id`',
    'SELECT ''bank_process_id already present, skipping''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. Add the lookup index if missing.
SET @idx_exists := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transactions' AND INDEX_NAME = 'idx_txn_bank_process'
);
SET @sql := IF(@idx_exists = 0,
    'ALTER TABLE `transactions` ADD KEY `idx_txn_bank_process` (`bank_process_id`)',
    'SELECT ''idx_txn_bank_process already present, skipping''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3. Add the FK constraint if missing.
SET @fk_exists := (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transactions' AND CONSTRAINT_NAME = 'fk_txn_bank_process'
);
SET @sql := IF(@fk_exists = 0,
    'ALTER TABLE `transactions` ADD CONSTRAINT `fk_txn_bank_process` FOREIGN KEY (`bank_process_id`) REFERENCES `bank_process` (`id`) ON DELETE SET NULL',
    'SELECT ''fk_txn_bank_process already present, skipping''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
