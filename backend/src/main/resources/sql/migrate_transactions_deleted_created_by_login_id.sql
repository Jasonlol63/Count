-- Align transactions_deleted with Spring Payment Maintenance archive
-- (tenant_id / remark / login_id strings / bank_process_posted_id).
--
-- Fix for: Data truncation: Data too long for column 'created_by' at row 1
-- when archiving deletes — usually means live DB still has INT created_by
-- (legacy PHP) while transactions.created_by is already VARCHAR login_id.
--
-- Review and adjust column renames if your DB partially migrated.
-- Backup before running.

-- 1) Widen / convert actor columns to login_id strings
ALTER TABLE `transactions_deleted`
    MODIFY COLUMN `created_by` VARCHAR(100) NULL COMMENT 'Submitter login_id';

-- If deleted_by does not exist yet (still deleted_by_user_id / deleted_by_owner_id):
--   ADD COLUMN `deleted_by` VARCHAR(100) NULL COMMENT 'Deleter login_id' AFTER `created_at`;
--   then backfill from user/owner if needed, then drop old columns.

ALTER TABLE `transactions_deleted`
    MODIFY COLUMN `deleted_by` VARCHAR(100) NULL COMMENT 'Deleter login_id';

-- 2) Prefer matching transactions.created_by length if you keep both at 50:
-- ALTER TABLE `transactions_deleted`
--     MODIFY COLUMN `created_by` VARCHAR(50) NULL,
--     MODIFY COLUMN `deleted_by` VARCHAR(50) NULL;

-- 3) Verify:
-- SHOW COLUMNS FROM transactions_deleted LIKE 'created_by';
-- SHOW COLUMNS FROM transactions LIKE 'created_by';
