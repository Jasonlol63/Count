-- One-off DATA CORRECTION (not raw migration): closes out the individual periodic due periods that
-- were left dangling after a RESEND_CONSOLIDATED request was SKIPPED in the legacy system.
--
-- Background: legacy PHP's Accounting Due Inbox only ever surfaces the CURRENT calendar month for
-- FIRST_OF_EVERY_MONTH/MONTHLY processes (process_accounting_inbox_api.php:1524, "非 Resend：仅当前自然月")
-- -- once a month rolls past without being Post/Skip'd, PHP silently stops asking about it (unless a
-- Resend explicitly reopens it). Spring Boot's BankAccountingDueServiceImpl.resolveFirstOfMonthDues
-- instead loops every month from the process's creation/day_start through today and keeps surfacing
-- any month with no exact (bank_process_id, posted_date, period_type) match in
-- bank_process_accounting_posted -- a deliberate behavior difference the user confirmed keeping (see
-- MIGRATION_LOG.md "Due 行为差异" discussion), but it means legacy processes whose individual months
-- were only ever "closed" via a Resend decision that got SKIPPED (not the individual months
-- themselves) now incorrectly resurface as due in the new Inbox.
--
-- Scope: found by querying (not guessing) which bank_process rows have a RESEND_CONSOLIDATED /
-- outcome=SKIPPED row in bank_process_accounting_posted -- only ONE such process exists in the
-- entire migrated database: bank_process.id=189 (CX / TRAVELMINI SDN BHD / RHB). All other
-- RESEND_CONSOLIDATED rows in the table are outcome=POSTED (a real consolidated bill was actually
-- collected), which is a settled outcome and not part of this problem.
--
-- For process 189 (FIRST_OF_EVERY_MONTH, day_start=2026-03-18, day_end=2026-09-17, status=ACTIVE,
-- day_end_monthly_cap_enabled=0, expired_at_creation=0), replicated
-- BankAccountingDueServiceImpl.buildFirstOfMonthDueForMonth's exact (postedDate, periodType) formula
-- by hand for every month from day_start through today (2026-08-28) and compared against the ledger:
--   2026-03-18 PARTIAL_FIRST_MONTH  -- no ledger row at all (gap)
--   2026-04-01 FULL_MONTH           -- no ledger row at all (gap)
--   2026-05-01 FULL_MONTH           -- no ledger row at all (gap)
--   2026-06-01 FULL_MONTH           -- POSTED (bank_process_accounting_posted.id 1544) -- already settled
--   2026-07-01 FULL_MONTH           -- POSTED (id 1649) -- already settled
--   2026-08-01 FULL_MONTH           -- POSTED (id 1693) -- already settled, current month
-- The only pre-existing ledger row before June is the RESEND_CONSOLIDATED SKIPPED itself at
-- 2026-03-01 (id 1416) -- a different period_type, so it doesn't satisfy the exact-key check for the
-- three gap months above.
--
-- Fix: insert SKIPPED rows for exactly those 3 gap periods, completing (at the per-month
-- granularity the new app actually checks) the "decline to bill this range" decision the admin
-- already made via the Resend-Skip. created_by intentionally NULL (this is a data correction, not a
-- real user action -- don't fabricate an actor); created_at = NOW() (this row is being created now,
-- not backdated to pretend it happened historically).
--
-- Idempotent: guarded by NOT EXISTS, safe to re-run.
--
-- Usage:
--   mysql -u root count_real < backend/src/main/resources/SqlEtcForMigrate/fix_bank_process_resend_skipped_due_gaps.sql

INSERT INTO bank_process_accounting_posted (tenant_id, bank_process_id, posted_date, period_type, outcome, created_at)
SELECT bp.tenant_id, bp.id, gap.posted_date, gap.period_type, 'SKIPPED', NOW()
FROM bank_process bp
CROSS JOIN (
    SELECT '2026-03-18' AS posted_date, 'PARTIAL_FIRST_MONTH' AS period_type
    UNION ALL SELECT '2026-04-01', 'FULL_MONTH'
    UNION ALL SELECT '2026-05-01', 'FULL_MONTH'
) gap
WHERE bp.id = 189
  AND NOT EXISTS (
      SELECT 1 FROM bank_process_accounting_posted bap
      WHERE bap.bank_process_id = bp.id
        AND bap.posted_date = gap.posted_date
        AND bap.period_type = gap.period_type
  );
