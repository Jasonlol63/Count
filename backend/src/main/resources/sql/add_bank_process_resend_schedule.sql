-- Add open Resend schedule columns to bank_process.
-- Safe for existing rows: all three are NULLABLE with DEFAULT NULL (no data rewrite).
-- Fresh installs already have these in schema.sql; run this only on existing DBs that lack them.

ALTER TABLE `bank_process`
    ADD COLUMN `resend_schedule_day_start` DATE DEFAULT NULL
        COMMENT 'Open make-up billing_start / posted anchor; NULL = no open Resend'
        AFTER `status`,
    ADD COLUMN `resend_schedule_day_end` DATE DEFAULT NULL
        COMMENT 'Open make-up billing_end (computed at Resend by frequency)'
        AFTER `resend_schedule_day_start`,
    ADD COLUMN `resend_schedule_frequency`
        ENUM('FIRST_OF_EVERY_MONTH', 'MONTHLY', 'ONCE', 'DAY', 'WEEK') DEFAULT NULL
        COMMENT 'Frequency chosen in Resend modal'
        AFTER `resend_schedule_day_end`;
