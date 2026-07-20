-- Drop unused outcome DISMISSED from bank_process_accounting_posted.
-- Spring Accounting Due only uses POSTED (post) and SKIPPED (delete/skip).
-- Old PHP "dismiss" maps to SKIPPED.

UPDATE `bank_process_accounting_posted`
SET `outcome` = 'SKIPPED'
WHERE `outcome` = 'DISMISSED';

ALTER TABLE `bank_process_accounting_posted`
  MODIFY COLUMN `outcome` ENUM('POSTED', 'SKIPPED') NOT NULL DEFAULT 'POSTED'
  COMMENT 'Replaces old period_type *_skipped suffix';
