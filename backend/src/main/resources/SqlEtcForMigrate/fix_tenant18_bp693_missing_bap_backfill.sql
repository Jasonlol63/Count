-- One-off DATA CORRECTION: creates the missing `bank_process_accounting_posted` row for bank_process
-- 693 (THE QIN RESTAURANT, HLB bank leg), tenant 18, and links the two orphan transactions to it.
--
-- Root cause (see SYNC_20260905_VERIFICATION.md §15.2): legacy's own `process_accounting_posted` table
-- never got a "resend consolidated / 2026-08-31" row for this bank_process (confirmed against the
-- 2026-09-07 11:49 dump too -- genuinely missing at the source, not a migration gap). Its sibling legs
-- 691 and 692 both DO have this second (2026-08-31) RESEND_CONSOLIDATED posting in count_real (bap ids
-- 3212 and 2927 respectively) -- 693 is the only one missing it. User confirmed: create it, using the
-- same "1/9-30/9" (RESEND_END=2026-09-30) resend window shown on count168.com's own copy of these two
-- rows.
--
-- Usage:
--   mysql -u root count_real < backend/src/main/resources/SqlEtcForMigrate/fix_tenant18_bp693_missing_bap_backfill.sql

START TRANSACTION;

INSERT INTO bank_process_accounting_posted (tenant_id, bank_process_id, posted_date, period_type, outcome, created_at)
VALUES (18, 693, '2026-08-31', 'RESEND_CONSOLIDATED', 'POSTED', NOW());

SET @new_bap_id := LAST_INSERT_ID();
SELECT @new_bap_id AS new_bap_id;

SELECT COUNT(*) AS should_be_2 FROM transactions WHERE id IN (151446,151449) AND tenant_id = 18;

UPDATE transactions
SET bank_process_posted_id = @new_bap_id
WHERE id IN (151446,151449) AND tenant_id = 18;

SELECT ROW_COUNT() AS rows_updated;

COMMIT;
