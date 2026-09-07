-- One-off DATA CORRECTION: removes a genuine legacy-side duplicate for bank_process 629 (MODA HOUSE
-- ENTERPRISE, BI bank leg), tenant 18. Unlike every other cleanup in this file, this is NOT a case of
-- "legacy deleted it, count_real never followed" -- verified against the 2026-09-07 11:49 legacy dump
-- (`c168_net_legacy_20260907`) that legacy itself currently has TWO live, identical transactions for
-- this exact leg (same account/amount/date/source_bank_process_id, created ~1 day apart: one batch
-- 2026-09-02 06:13:10, another 2026-09-03 05:50:47) -- neither is soft-deleted on the legacy side.
-- User confirmed (comparing count168.com's Payment History, which only shows one copy) that the later
-- batch is the extra one to remove; keeping the earlier-migrated (id-preserved) rows as canonical.
--
-- Affected accounts: CR10 (5581), AG4 (5582), SU13 (5583), 23 GROUP (5554) -- all 4 legs of the same
-- bank_process_accounting_posted id=2815 posting.
--
-- Usage:
--   mysql -u root count_real < backend/src/main/resources/SqlEtcForMigrate/fix_tenant18_moda_house_bp629_duplicate_cleanup.sql

START TRANSACTION;

SELECT COUNT(*) AS should_be_4 FROM transactions WHERE id IN (151418,151419,151420,151421) AND tenant_id = 18;

INSERT INTO transactions_deleted
    (tenant_id, transaction_id, transaction_type, account_id, from_account_id, currency_id, amount,
     transaction_date, description, remark, created_by, created_at, deleted_by, deleted_at,
     bank_process_posted_id, rate_group_id)
SELECT
    t.tenant_id, t.id, t.transaction_type, t.account_id, t.from_account_id, t.currency_id, t.amount,
    t.transaction_date, t.description, t.remark, t.created_by, t.created_at,
    'SYSTEM_DEDUP', NOW(),
    t.bank_process_posted_id, t.rate_group_id
FROM transactions t
WHERE t.id IN (151418,151419,151420,151421) AND t.tenant_id = 18;

SELECT ROW_COUNT() AS rows_archived;

DELETE FROM transactions WHERE id IN (151418,151419,151420,151421) AND tenant_id = 18;

SELECT ROW_COUNT() AS rows_deleted;

COMMIT;
