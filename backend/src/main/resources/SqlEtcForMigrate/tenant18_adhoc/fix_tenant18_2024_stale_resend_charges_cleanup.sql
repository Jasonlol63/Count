-- One-off DATA CORRECTION: removes 7 stale 2024-dated transactions for tenant 18 ("23" company) that
-- were causing a spurious opening balance (B/F) to appear in August 2026 reports.
--
-- Root cause: these 7 rows (id 151302-151308) are "Process: ... (resend consolidated)" postings for
-- bank_process 599 (PAPPA 34 ENTERPRISE, 2024-06-27) and 663 (J & J AUTO GARAGE, 2024-11-28), almost
-- certainly captured by the original 2026-08-27 full migration (their id range predates the 9/3/9/5
-- delta rounds' fresh-id blocks). Verified: legacy currently has ZERO active `transactions` for either
-- bank_process before 2025-01-01 -- every generation it ever posted (two confirmed generations per
-- contract, ids 18239-18242/19082-19085 for bp599 and 18255-18257/19250-19252 for bp663) has since been
-- deleted by legacy itself (deleted_at 2026-09-02, owner K23), even though the originating
-- `process_accounting_posted` row (1891/2350) still shows POSTED. Same "legacy deleted it, count_real
-- never followed" pattern as §2.1/§12/§13 -- just from further back than the 9/3->9/5 comparison window
-- those covered, so it was never caught until now.
--
-- Unlike §12/§13's rows, these 7 do NOT already have a `transactions_deleted` archive entry (checked
-- before writing this script) -- this deletion evidently was never synced into count_real at all, at
-- any point -- so this script both archives AND removes them.
--
-- Usage:
--   mysql -u root count_real < backend/src/main/resources/SqlEtcForMigrate/fix_tenant18_2024_stale_resend_charges_cleanup.sql

START TRANSACTION;

SELECT COUNT(*) AS should_be_7 FROM transactions WHERE id IN (151302,151303,151304,151305,151306,151307,151308) AND tenant_id = 18;

INSERT INTO transactions_deleted
    (tenant_id, transaction_id, transaction_type, account_id, from_account_id, currency_id, amount,
     transaction_date, description, remark, created_by, created_at, deleted_by, deleted_at,
     bank_process_posted_id, rate_group_id)
SELECT
    t.tenant_id, t.id, t.transaction_type, t.account_id, t.from_account_id, t.currency_id, t.amount,
    t.transaction_date, t.description, t.remark, t.created_by, t.created_at,
    'K23',
    CASE WHEN t.id IN (151302,151303,151304,151305) THEN '2026-09-02 06:08:00'
         ELSE '2026-09-02 06:08:28' END,
    t.bank_process_posted_id, t.rate_group_id
FROM transactions t
WHERE t.id IN (151302,151303,151304,151305,151306,151307,151308) AND t.tenant_id = 18;

SELECT ROW_COUNT() AS rows_archived;

DELETE FROM transactions WHERE id IN (151302,151303,151304,151305,151306,151307,151308) AND tenant_id = 18;

SELECT ROW_COUNT() AS rows_deleted;

COMMIT;
