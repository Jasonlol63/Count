-- Follow-up to migrate_delta_datacapture_20260905.sql -- that script correctly copied the 126 new
-- data_capture_line rows (delta since 2026-09-03), but (like the delta script it was modeled on)
-- never created the corresponding `transactions` WIN/LOSE row nor set `transaction_id` -- the same gap
-- migrate_data_capture_line_transactions_backfill.sql fixed once for the original 75234 rows.
--
-- Discovered via user report: RS company, account BZA-312, capture_date 2026-09-03 shows Win/Loss
-- 0.00 in count_real's daily summary despite count168.com (production) showing 5 real entries
-- (SCRM10 API captures, capture_id 20182-20186) -- the underlying data_capture_line rows were present
-- with correct amounts, but TransactionHistoryServiceImpl's Payment History / balance rollups read
-- from `transactions`, not `data_capture_line` directly, so a line with no linked transaction is
-- invisible everywhere except the raw Data Capture Summary screen.
--
-- Scope verified before writing: database-wide, ALL data_capture_line rows have transaction_id set
-- EXCEPT exactly 126 -- which are exactly the 126 rows inserted by this round's delta (2026-09-05).
-- No other rows/rounds affected. All 126 join cleanly to data_captures/process, formula non-empty.
--
-- Field mapping mirrors migrate_data_capture_line_transactions_backfill.sql exactly (matches
-- DataCaptureSummaryServiceImpl.toTransaction()/toLineEntity()), scoped to WHERE transaction_id IS
-- NULL so it cannot touch the 77859 already-linked rows from the original backfill.
--
-- Usage:
--   mysql -u root count_real < backend/src/main/resources/SqlEtcForMigrate/migrate_delta_datacapture_line_transactions_backfill_20260905.sql

START TRANSACTION;

SET @start_txn_id = (SELECT MAX(id) + 1 FROM transactions);

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
WHERE dcl.transaction_id IS NULL
ORDER BY dcl.id;

UPDATE data_capture_line dcl
JOIN (
    SELECT
        dcl2.id AS line_id,
        @start_txn_id + (ROW_NUMBER() OVER (ORDER BY dcl2.id)) - 1 AS new_txn_id
    FROM data_capture_line dcl2
    WHERE dcl2.transaction_id IS NULL
) mapping ON mapping.line_id = dcl.id
SET dcl.transaction_id = mapping.new_txn_id;

COMMIT;
