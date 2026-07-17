-- Resend redesign (make-up bill, parallel to normal Accounting Due):
--   - Drop resend_pending (not a Maintenance "go Resend" todo)
--   - Drop resend_relax_created_floor (make-up does not override normal Due calc)
--   - Keep resend_schedule_* = at most one open make-up; new Resend overwrites
--   - Make-up ledger rows use period_type=RESEND_CONSOLIDATED (already in enum)
--   - bank_process_resend_daily_guard = Post same-day lock by day_start (freq ignored);
--     clear on Maintenance txn delete
--
-- Safe for DBs that already applied the older bank_process resend columns.

ALTER TABLE `bank_process`
    DROP INDEX `idx_bp_tenant_resend_pending`,
    DROP COLUMN `resend_pending`,
    DROP COLUMN `resend_relax_created_floor`;

ALTER TABLE `bank_process`
    MODIFY COLUMN `resend_schedule_day_start` DATE DEFAULT NULL
        COMMENT 'Open make-up billing_start / posted anchor; NULL = no open Resend',
    MODIFY COLUMN `resend_schedule_day_end` DATE DEFAULT NULL
        COMMENT 'Open make-up billing_end (computed at Resend by frequency)',
    MODIFY COLUMN `resend_schedule_frequency`
        ENUM('FIRST_OF_EVERY_MONTH', 'MONTHLY', 'ONCE', 'DAY', 'WEEK') DEFAULT NULL
        COMMENT 'Frequency chosen in Resend modal';
