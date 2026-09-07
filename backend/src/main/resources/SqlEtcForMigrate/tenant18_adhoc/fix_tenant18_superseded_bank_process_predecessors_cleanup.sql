-- One-off DATA CORRECTION: companion to fix_tenant18_bank_process_posted_id_relink_20260905round.sql.
-- For each of the 18 "resend consolidated" bank_process periods fixed by that script, legacy had
-- already superseded one or more EARLIER postings for the exact same period (its own resend engine
-- re-triggers repeatedly, each time invalidating the previous batch) -- those earlier postings were
-- migrated into count_real (id-preserved) in an earlier round and, per the standing "only add new,
-- never retroactively apply upstream deletions" policy (SYNC_20260905_VERIFICATION.md §2.1), were
-- never removed even though legacy itself deleted them. Confirmed via each row's own
-- `source_bank_process_id` in legacy's transactions_deleted archive (not a business-key guess) --
-- 72 rows, all tenant 18.
--
-- Action: mirror legacy's own soft-delete (move into `transactions_deleted` with legacy's own
-- deleted_at/deleted_by) and remove from the live `transactions` table -- same treatment as the CR20
-- CONTRA cleanup in §12.
--
-- Usage:
--   mysql -u root count_real < backend/src/main/resources/SqlEtcForMigrate/fix_tenant18_superseded_bank_process_predecessors_cleanup.sql

START TRANSACTION;

CREATE TEMPORARY TABLE _to_delete AS
SELECT DISTINCT t.id AS txn_id
FROM count_real.transactions t
JOIN c168_net_legacy_20260905.transactions_deleted ltd
    ON ltd.transaction_id = t.id
   AND ltd.company_id = 325
   AND ltd.source_bank_process_id IN (599,602,603,604,605,617,629,646,647,648,660,661,669,670,691,692,693)
WHERE t.tenant_id = 18;

-- Sanity check: must be exactly 72 rows before applying.
SELECT COUNT(*) AS should_be_72 FROM _to_delete;

-- IMPORTANT (lesson from the §12 CR20 cleanup): every one of these 72 rows ALREADY has its own
-- `transactions_deleted` archive entry (inserted by the original migration round that first brought it
-- in) -- verified before writing this script. So this cleanup does NOT insert into transactions_deleted
-- again (that would create duplicate archive rows, exactly the mistake caught and fixed in §12) -- it
-- only removes the stale duplicate from the live `transactions` table.
SELECT COUNT(*) AS should_also_be_72_already_archived
FROM _to_delete d
JOIN transactions_deleted td ON td.transaction_id = d.txn_id AND td.tenant_id = 18;

DELETE t FROM transactions t JOIN _to_delete d ON d.txn_id = t.id;

SELECT ROW_COUNT() AS rows_deleted;

DROP TEMPORARY TABLE _to_delete;

COMMIT;
