-- Extends `transactions_deleted` with approval-snapshot columns so the Contra Inbox "Reject" action
-- can archive a PENDING transaction the same way Payment/Bank Process Maintenance already archives
-- deleted rows: INSERT INTO transactions_deleted first, then DELETE FROM transactions (see
-- MaintenanceServiceImpl.deletePaymentMaintenanceRows / deleteBankProcessMaintenanceRows for the
-- existing archive-then-hard-delete pattern this reuses).
--
-- `approval_status` only ever needs APPROVED or REJECTED here (not PENDING):
--   - APPROVED: existing Maintenance-delete paths, which only ever archive already-approved rows
--     (see archiveBankProcessMaintenanceToDeleted's `approval_status = 'APPROVED'` filter) — default
--     value backfills all pre-existing archived rows and future non-Contra-Inbox deletes.
--   - REJECTED: new Contra Inbox reject flow archives a PENDING row as REJECTED; the row is removed
--     from `transactions` in the same transaction, so REJECTED only ever exists here, never live.
-- `approved_by` / `approved_at` mirror `transactions.approved_by` / `approved_at` at archive time;
-- for a Contra Inbox rejection these are the rejecting manager/admin/owner and the reject timestamp
-- (same actor/time as `deleted_by` / `deleted_at` — kept as separate columns to mirror the source
-- row's own fields exactly, consistent with the rest of this table's snapshot columns).
--
-- Safe to re-run: uses information_schema checks before each ADD COLUMN.
-- Example: mysql -u root count_real < backend/src/main/resources/sql/migrate_add_contra_inbox_approval_to_transactions_deleted.sql

-- 1. approval_status
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transactions_deleted' AND COLUMN_NAME = 'approval_status'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE `transactions_deleted` ADD COLUMN `approval_status` ENUM(''APPROVED'', ''REJECTED'') NOT NULL DEFAULT ''APPROVED'' COMMENT ''Snapshot at archive time: APPROVED = normal Maintenance delete of an already-approved row; REJECTED = Contra Inbox rejection of a PENDING row'' AFTER `remark`',
    'SELECT ''approval_status already present on transactions_deleted, skipping''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. approved_by
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transactions_deleted' AND COLUMN_NAME = 'approved_by'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE `transactions_deleted` ADD COLUMN `approved_by` VARCHAR(50) DEFAULT NULL COMMENT ''Approver/rejecter login_id, mirrors transactions.approved_by at archive time'' AFTER `approval_status`',
    'SELECT ''approved_by already present on transactions_deleted, skipping''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3. approved_at
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transactions_deleted' AND COLUMN_NAME = 'approved_at'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE `transactions_deleted` ADD COLUMN `approved_at` TIMESTAMP NULL DEFAULT NULL COMMENT ''Mirrors transactions.approved_at at archive time'' AFTER `approved_by`',
    'SELECT ''approved_at already present on transactions_deleted, skipping''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
