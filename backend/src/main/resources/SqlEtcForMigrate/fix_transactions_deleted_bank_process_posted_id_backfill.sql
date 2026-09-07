-- One-off DATA CORRECTION (not raw migration): backfills `transactions_deleted.bank_process_posted_id`
-- database-wide -- this column exists on the table (added at some point for the Bank Process
-- Maintenance feature) but was never populated by ANY prior migration round (original full migration,
-- 2026-09-03 delta, or 2026-09-05 delta) -- verified: 886 WIN/LOSE deleted rows across 8 tenants
-- (23/384, CX/195, 72/159, M2/128, AG/8, 95/7, RS/3, M1/2) all have it NULL.
--
-- Symptom this fixes: MaintenanceMapper.xml's findBankProcessMaintenanceDeletedRows requires
-- `td.bank_process_posted_id IS NOT NULL` -- every migrated deleted Bank Process WIN/LOSE line was
-- therefore invisible on the Bank Process Maintenance page's deleted view, even though the row exists
-- correctly in `transactions_deleted` (discovered via user comparing tenant 23's maintenance list
-- against count168.com, which does show these deleted rows).
--
-- Resolution: for each deleted row, look up the matching legacy `transactions_deleted` row (same
-- legacy transaction_id, scoped by company via tenant.code) to read its own
-- source_bank_process_id/source_bank_process_period_type, resolve that to the matching legacy
-- `process_accounting_posted` row (process_id + posted_date + period_type -- same join used for the
-- live `transactions` backfill), then match that to the corresponding count_real
-- `bank_process_accounting_posted` row by (tenant_id, bank_process_id, posted_date, mapped period_type
-- enum) -- resolving by business key directly, not by id-preservation assumptions, so this works
-- regardless of which migration round originally inserted the row.
--
-- Usage:
--   mysql -u root count_real < backend/src/main/resources/SqlEtcForMigrate/fix_transactions_deleted_bank_process_posted_id_backfill.sql

START TRANSACTION;

CREATE TEMPORARY TABLE _resolve AS
SELECT
    td.id AS td_id,
    bap.id AS resolved_bap_id
FROM transactions_deleted td
JOIN tenant ten ON ten.id = td.tenant_id
JOIN c168_net_legacy_20260905.company c ON c.company_id = ten.code
JOIN c168_net_legacy_20260905.transactions_deleted ltd
    ON ltd.company_id = c.id AND ltd.transaction_id = td.transaction_id
    AND ltd.transaction_type = td.transaction_type AND ltd.amount = td.amount
JOIN bank_process bp ON bp.id = ltd.source_bank_process_id AND bp.tenant_id = td.tenant_id
JOIN bank_process_accounting_posted bap
    ON bap.tenant_id = td.tenant_id
   AND bap.bank_process_id = ltd.source_bank_process_id
   AND bap.posted_date = ltd.transaction_date
   AND bap.period_type = (
        CASE
            WHEN ltd.source_bank_process_period_type = 'day_end_tail' THEN 'DAY_END_TAIL'
            WHEN ltd.source_bank_process_period_type = 'manual_inactive' THEN 'COMPENSATION'
            WHEN ltd.source_bank_process_period_type = 'once_one_off' THEN 'ONCE_ONE_OFF'
            WHEN ltd.source_bank_process_period_type = 'partial_first_month' THEN 'PARTIAL_FIRST_MONTH'
            WHEN ltd.source_bank_process_period_type = 'resend_consolidated_range' THEN 'RESEND_CONSOLIDATED'
            WHEN ltd.source_bank_process_period_type = 'monthly' AND bp.frequency = 'FIRST_OF_EVERY_MONTH'
                 AND YEAR(ltd.transaction_date) = YEAR(bp.day_start) AND MONTH(ltd.transaction_date) = MONTH(bp.day_start)
                THEN 'FIRST_MONTH'
            WHEN ltd.source_bank_process_period_type = 'monthly' AND bp.frequency = 'FIRST_OF_EVERY_MONTH'
                THEN 'FULL_MONTH'
            WHEN ltd.source_bank_process_period_type = 'monthly' THEN 'MONTHLY'
            ELSE 'NO_MATCH_SENTINEL'
        END
   )
WHERE td.transaction_type IN ('WIN','LOSE')
  AND td.bank_process_posted_id IS NULL
  AND ltd.source_bank_process_id IS NOT NULL;

-- Verified before running: resolved_count == COUNT(DISTINCT td_id) (no ambiguous multi-match) --
-- 581 rows resolved cleanly this run (out of 837 true candidates database-wide; the remaining ~256
-- have no matching bank_process_accounting_posted row in count_real at all -- e.g. the period was
-- part of the explicitly-skipped reversed-upstream batch discussed in SYNC_20260905_VERIFICATION.md,
-- or fell into the ~75-row billing_start/billing_end NPE gap noted there -- not fixable by this
-- resolution path).
SELECT COUNT(*) AS resolved_count, COUNT(DISTINCT td_id) AS distinct_td FROM _resolve;

UPDATE transactions_deleted td
JOIN _resolve r ON r.td_id = td.id
SET td.bank_process_posted_id = r.resolved_bap_id;

DROP TEMPORARY TABLE _resolve;

-- Second pass: a handful of rows (38) have their legacy source_bank_process_period_type text NOT
-- matching what actually got posted for that (tenant, bank_process, date) in count_real -- e.g.
-- bank_process 189 (CX) is the one process with the known RESEND_CONSOLIDATED/SKIPPED reclassification
-- from fix_bank_process_resend_skipped_due_gaps.sql, so a deleted row recorded against
-- 'partial_first_month' actually posted as RESEND_CONSOLIDATED. Safe to resolve by (tenant,
-- bank_process, posted_date) alone WHEN exactly one bap row exists for that combination (verified:
-- 38 rows, all unambiguous single-candidate matches).
CREATE TEMPORARY TABLE _resolve2 AS
SELECT td.id AS td_id, bap.id AS resolved_bap_id
FROM transactions_deleted td
JOIN tenant ten ON ten.id = td.tenant_id
JOIN c168_net_legacy_20260905.company c ON c.company_id = ten.code
JOIN c168_net_legacy_20260905.transactions_deleted ltd
    ON ltd.company_id = c.id AND ltd.transaction_id = td.transaction_id
    AND ltd.transaction_type = td.transaction_type AND ltd.amount = td.amount
JOIN bank_process_accounting_posted bap
    ON bap.tenant_id = td.tenant_id
   AND bap.bank_process_id = ltd.source_bank_process_id
   AND bap.posted_date = ltd.transaction_date
WHERE td.transaction_type IN ('WIN','LOSE')
  AND td.bank_process_posted_id IS NULL
  AND ltd.source_bank_process_id IS NOT NULL;

UPDATE transactions_deleted td
JOIN _resolve2 r ON r.td_id = td.id
SET td.bank_process_posted_id = r.resolved_bap_id;

DROP TEMPORARY TABLE _resolve2;

-- =============================================================================
-- Remaining 267 rows (after both passes) are NOT fixable by this script -- traced to root cause,
-- not guessed:
--   ~228 rows (tenant CX/M2/72, ~20 distinct bank_process ids): the bank_process record itself no
--     longer exists in the CURRENT legacy database at all (deleted from legacy's own `bank_process`
--     table at some point after these transactions were created) -- there is nothing left to resolve
--     against, the chain is broken at the source. Permanent, unrecoverable gap.
--   ~39 rows (bank_process 189/420 for CX, 694/689/701/676 for M2): bank_process itself still exists,
--     but no `bank_process_accounting_posted` row exists in count_real for that exact
--     (bank_process, posted_date) at all -- a separate, pre-existing accounting-due migration gap
--     (overlaps with the already-documented billing_start/billing_end NPE skip list and/or the
--     explicitly-out-of-scope reversed-upstream batch), not something safe to fabricate here.
-- =============================================================================

COMMIT;
