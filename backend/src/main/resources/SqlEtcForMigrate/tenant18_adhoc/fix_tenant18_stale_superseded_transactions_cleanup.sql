-- One-off DATA CORRECTION: remove 5 transactions from tenant 18 ("23" company) that legacy soft-deleted
-- (moved into its own `transactions_deleted`) between the 9/3 and 9/5 snapshots, but which `count_real`
-- kept active because the delta-sync policy is "only add new, never retroactively apply upstream
-- deletions" (see SYNC_20260905_VERIFICATION.md §2.1). These 5 are the subset of that 81-row category
-- that can be identified with 100% certainty (unique tenant+type+amount+date+account+from_account match,
-- no ambiguity from repeated "resend consolidated" business content) -- see §11/§12 discussion. The
-- remaining ~76 rows in that category are NOT included here: their (amount, date, account) combination
-- matches multiple current count_real rows because the legacy resend-consolidated engine re-posts the
-- same business content with new ids repeatedly, and the one-off `_new_txn_map` used to migrate them is
-- long dropped, so which specific count_real row corresponds to which vanished legacy id cannot be
-- determined safely from available data. Handling those case-by-case as the user finds them.
--
-- Action: move each row into `transactions_deleted` (mirroring legacy's own soft-delete, so the audit
-- trail / Bank Process Maintenance "deleted" tab reflects it) and remove it from the live `transactions`
-- table (this changes live account balances -- that's the point, it corrects the double-count).
--
-- Usage:
--   mysql -u root count_real < backend/src/main/resources/SqlEtcForMigrate/fix_tenant18_stale_superseded_transactions_cleanup.sql

START TRANSACTION;

-- Sanity check: must be exactly 5 rows, all tenant 18, before touching anything.
SELECT COUNT(*) AS should_be_5 FROM transactions WHERE id IN (18474,19850,20011,20012,20118) AND tenant_id = 18;

INSERT INTO transactions_deleted
    (tenant_id, transaction_id, transaction_type, account_id, from_account_id, currency_id, amount,
     transaction_date, description, remark, created_by, created_at, deleted_by, deleted_at,
     bank_process_posted_id, rate_group_id)
SELECT
    t.tenant_id, t.id, t.transaction_type, t.account_id, t.from_account_id, t.currency_id, t.amount,
    t.transaction_date, t.description, t.remark, t.created_by, t.created_at,
    src.deleted_by, src.deleted_at,
    t.bank_process_posted_id, t.rate_group_id
FROM transactions t
JOIN (
    SELECT 18474 AS id, 'K23' AS deleted_by, '2026-09-03 05:01:29' AS deleted_at UNION ALL
    SELECT 19850, 'K23', '2026-09-03 06:28:52' UNION ALL
    SELECT 20011, 'K23', '2026-09-03 06:43:41' UNION ALL
    SELECT 20012, 'K23', '2026-09-03 06:43:41' UNION ALL
    SELECT 20118, 'K23', '2026-09-03 06:30:43'
) src ON src.id = t.id
WHERE t.tenant_id = 18;

DELETE FROM transactions WHERE id IN (18474,19850,20011,20012,20118) AND tenant_id = 18;

-- Verify: 0 rows left active, 5 rows now in transactions_deleted.
SELECT COUNT(*) AS should_be_0 FROM transactions WHERE id IN (18474,19850,20011,20012,20118);
SELECT COUNT(*) AS should_be_5_too FROM transactions_deleted WHERE transaction_id IN (18474,19850,20011,20012,20118) AND tenant_id = 18;

COMMIT;
