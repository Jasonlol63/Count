-- One-off DATA migration (backfill, not raw migration): creates the missing `transactions` WIN/LOSE
-- row for every migrated `data_capture_line`, and links it back via `data_capture_line.transaction_id`.
--
-- Background: the earlier Data Capture domain migration (MIGRATION_LOG.md §5) correctly copied
-- `data_capture_details` -> `data_capture_line` (75234 rows, count verified at the time), but never
-- created the corresponding `transactions` row for each line, nor set `transaction_id`. This wasn't
-- visible until now because the OLD legacy PHP system's Payment History reads straight from
-- `data_capture_details` -- it never needed a `transactions` row for these at all. The NEW Spring
-- Boot app is designed differently: every Data Capture Summary submission writes a real `transactions`
-- WIN/LOSE row AND sets `data_capture_line.transaction_id` (see
-- DataCaptureSummaryServiceImpl.submitSummary -> toTransaction()/toLineEntity()), and
-- TransactionHistoryServiceImpl's Payment History reads that `transactions` row as the source of
-- truth, only using `data_capture_line` for supplementary display fields (idProduct, rateExpression).
-- Migrated data never went through that path, so every migrated line's amount is invisible in Payment
-- History (and in any account-balance rollup that sums `transactions`) -- discovered when
-- KY/KAI YUAN's two SALARY entries were missing.
--
-- Scope: found by querying (not guessing) -- ALL 75234 data_capture_line rows have
-- transaction_id IS NULL confirmed database-wide, across 12 tenants / 926 accounts / 12893 captures
-- (12852 GAME + 41 BANK). Verified before writing this: 0 orphaned capture_id, 0 orphaned process_id,
-- 0 orphaned account_id/currency_id/tenant_id -- the data is clean, this really is "never created",
-- not "created but broken".
--
-- Field mapping mirrors DataCaptureSummaryServiceImpl.toTransaction()/toLineEntity() exactly, so a
-- migrated row is indistinguishable in shape from one the running app would have produced itself:
--   transaction_type   = processed_amount > 0 ? WIN : LOSE   (732 lines are exactly 0 -- app's own
--                         signum() > 0 check treats those as LOSE amount 0 too; not special-cased
--                         here either, matches real app behavior for a zero-amount line)
--   amount              = ABS(processed_amount)
--   account_id/currency_id = the line's own (not the header's -- matches toTransaction())
--   transaction_date    = data_captures.capture_date
--   description         = process.code + ": " + formula   (all 75234 lines have a non-empty formula,
--                          verified -- toTransaction()'s "no formula -> formatted amount" fallback
--                          path is never exercised by this backfill)
--   remark              = MAIN line -> description_main, SUB line -> description_sub (matches
--                          resolveLineRemark(); never falls back to the other type's text)
--   created_by / approved_by = data_captures.created_by (0 captures have a NULL creator, verified)
--   approval_status     = APPROVED (these are historical, already-submitted captures)
--   created_at / approved_at = data_captures.created_at (the real historical submit time -- not
--                          NOW(), this genuinely happened back then, don't fabricate a fresh
--                          timestamp for a backdated event)
--   from_account_id / bank_process_posted_id / rate_group_id = left NULL (column defaults; WIN/LOSE
--                          rows never have these, matches toTransaction())
--
-- id assignment: `transactions.id` is NOT preserved from anywhere (there is no legacy transactions
-- row to preserve an id from -- these never existed before). New ids are assigned by AUTO_INCREMENT
-- in the exact order the backfill INSERT...SELECT below produces rows (ORDER BY dcl.id), and the
-- follow-up UPDATE recomputes the same ordering with ROW_NUMBER() to map each data_capture_line back
-- to the transaction id its own row produced. This relies on MySQL/MariaDB inserting
-- INSERT...SELECT ... ORDER BY rows in that exact order with strictly sequential AUTO_INCREMENT
-- assignment, which holds for a single-statement, single-session bulk insert like this (no concurrent
-- writers during migration) -- verified after running by spot-checking that the mapped pairs' account
-- /amount/date actually match, not just trusted blindly.
--
-- Idempotency: NOT idempotent -- intended for a single run while every data_capture_line.transaction_id
-- is still NULL. Re-running would create a second set of duplicate transactions.
--
-- Usage:
--   mysql -u root count_real < backend/src/main/resources/SqlEtcForMigrate/migrate_data_capture_line_transactions_backfill.sql

SET @start_txn_id = (
    SELECT AUTO_INCREMENT FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transactions'
);

INSERT INTO transactions (
    tenant_id, transaction_type, account_id, currency_id, amount, transaction_date,
    description, remark, created_by, approval_status, approved_by, approved_at,
    created_at, updated_at
)
SELECT
    dcl.tenant_id,
    CASE WHEN dcl.processed_amount > 0 THEN 'WIN' ELSE 'LOSE' END,
    dcl.account_id,
    dcl.currency_id,
    ABS(dcl.processed_amount),
    dc.capture_date,
    CONCAT(p.code, ': ', dcl.formula),
    CASE WHEN dcl.product_type = 'MAIN' THEN dcl.description_main ELSE dcl.description_sub END,
    dc.created_by,
    'APPROVED',
    dc.created_by,
    dc.created_at,
    dc.created_at,
    dc.created_at
FROM data_capture_line dcl
JOIN data_captures dc ON dc.id = dcl.capture_id
JOIN process p ON p.id = dc.process_id
ORDER BY dcl.id;

UPDATE data_capture_line dcl
JOIN (
    SELECT
        dcl2.id AS line_id,
        @start_txn_id + (ROW_NUMBER() OVER (ORDER BY dcl2.id)) - 1 AS new_txn_id
    FROM data_capture_line dcl2
) mapping ON mapping.line_id = dcl.id
SET dcl.transaction_id = mapping.new_txn_id;
