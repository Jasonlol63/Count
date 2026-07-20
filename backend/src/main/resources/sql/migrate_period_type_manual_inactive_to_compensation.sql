-- Rename ledger period_type MANUAL_INACTIVE → COMPENSATION (1+N contract penalty due).
-- Run once on existing databases that already have bank_process_accounting_posted.

ALTER TABLE `bank_process_accounting_posted`
  MODIFY COLUMN `period_type` ENUM(
    'MONTHLY',
    'FIRST_MONTH',
    'PARTIAL_FIRST_MONTH',
    'FULL_MONTH',
    'DAY_END_TAIL',
    'ONCE_ONE_OFF',
    'MANUAL_INACTIVE',
    'COMPENSATION',
    'RESEND_CONSOLIDATED',
    'WEEKLY',
    'DAILY',
    'DAILY_CONSOLIDATED'
  ) NOT NULL DEFAULT 'MONTHLY';

UPDATE `bank_process_accounting_posted`
SET `period_type` = 'COMPENSATION'
WHERE `period_type` = 'MANUAL_INACTIVE';

ALTER TABLE `bank_process_accounting_posted`
  MODIFY COLUMN `period_type` ENUM(
    'MONTHLY',
    'FIRST_MONTH',
    'PARTIAL_FIRST_MONTH',
    'FULL_MONTH',
    'DAY_END_TAIL',
    'ONCE_ONE_OFF',
    'COMPENSATION',
    'RESEND_CONSOLIDATED',
    'WEEKLY',
    'DAILY',
    'DAILY_CONSOLIDATED'
  ) NOT NULL DEFAULT 'MONTHLY';
