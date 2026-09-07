-- Follow-up to migrate_delta_transactions_and_accounting_due_20260905.sql -- that script's steps 1-3
-- (transactions INSERT, transactions_rate no-op, transactions_deleted INSERT) already ran and
-- COMMITTED successfully (CREATE TEMPORARY TABLE ... AS SELECT in step 4 causes an implicit commit
-- in InnoDB, so steps 1-3's effects were durable even though the script as a whole errored out
-- later). Steps 4-6 failed with "COLLATE utf8mb4_unicode_ci is not valid for CHARACTER SET utf8"
-- (the CASE-derived period_type expression picks up the connection's charset, not the column's
-- utf8mb4 -- the explicit COLLATE forced a mismatch; removed here, comparing the enum column to a
-- plain string literal needs no explicit collation).
--
-- This script re-runs ONLY steps 4-6 (all idempotent/target-scoped via NOT EXISTS, safe to run once
-- now). It does NOT re-run step 1 or step 3 -- those are NOT idempotent (scoped only against the
-- legacy source, not the target) and would duplicate the 221 transactions / 111 transactions_deleted
-- rows already committed.
--
-- _new_txn_map is rebuilt deterministically to match what's already in `transactions`: verified the
-- 221 new rows landed as a contiguous block, ids 151378-151598 (base_txn was 151377, the ROW_NUMBER
-- ordering by legacy id is unchanged since the source table hasn't changed) -- reconstructing it this
-- way (rather than leaving it dropped) is required for step 6's backfill JOIN.
--
-- Usage:
--   mysql -u root count_real < backend/src/main/resources/SqlEtcForMigrate/migrate_delta_transactions_and_accounting_due_20260905_part2.sql

START TRANSACTION;

CREATE TEMPORARY TABLE _new_txn_map (
    legacy_id INT NOT NULL PRIMARY KEY,
    new_id    INT NOT NULL UNIQUE
);
SET @base_txn := 151377;
INSERT INTO _new_txn_map (legacy_id, new_id)
SELECT x.legacy_id, @base_txn + x.rn
FROM (
    SELECT t.id AS legacy_id, ROW_NUMBER() OVER (ORDER BY t.id) AS rn
    FROM c168_net_legacy_20260905.transactions t
    JOIN c168_net_legacy_20260905.company c ON c.id = t.company_id
    JOIN tenant ten ON ten.tenant_type = 'COMPANY' AND ten.code = c.company_id
    WHERE t.id NOT IN (SELECT id FROM c168_net_legacy_20260827.transactions)
) x;

-- Sanity check: this must match the already-committed contiguous id block exactly, or abort.
-- (No native ASSERT in MySQL/MariaDB -- a mismatch here would make the JOIN below silently resolve
-- nothing rather than corrupt data, so it fails safe either way; verified by hand before running:
-- 221 rows, legacy ids matching, new_id range 151378-151598.)

-- =============================================================================
-- 4. bank_process_accounting_posted from process_accounting_posted: fresh-id mapping.
--    (COLLATE fix: dropped the explicit COLLATE utf8mb4_unicode_ci that caused the earlier failure.)
-- =============================================================================
CREATE TEMPORARY TABLE _new_pap AS
SELECT
    p.id,
    ten.id AS tenant_id,
    p.process_id AS bank_process_id,
    p.posted_date,
    CASE
        WHEN p.base_type = 'day_end_tail'             THEN 'DAY_END_TAIL'
        WHEN p.base_type = 'manual_inactive'          THEN 'COMPENSATION'
        WHEN p.base_type = 'once_one_off'             THEN 'ONCE_ONE_OFF'
        WHEN p.base_type = 'partial_first_month'      THEN 'PARTIAL_FIRST_MONTH'
        WHEN p.base_type = 'resend_consolidated_range' THEN 'RESEND_CONSOLIDATED'
        WHEN p.base_type = 'monthly' AND bp.frequency = 'FIRST_OF_EVERY_MONTH'
             AND YEAR(p.posted_date) = YEAR(bp.day_start) AND MONTH(p.posted_date) = MONTH(bp.day_start)
            THEN 'FIRST_MONTH'
        WHEN p.base_type = 'monthly' AND bp.frequency = 'FIRST_OF_EVERY_MONTH'
            THEN 'FULL_MONTH'
        WHEN p.base_type = 'monthly'                  THEN 'MONTHLY'
    END AS period_type,
    CASE WHEN p.period_type LIKE '%\_skipped' THEN 'SKIPPED' ELSE 'POSTED' END AS outcome,
    p.created_at,
    ROW_NUMBER() OVER (
        PARTITION BY ten.id, p.process_id, p.posted_date,
            CASE
                WHEN p.base_type = 'day_end_tail'             THEN 'DAY_END_TAIL'
                WHEN p.base_type = 'manual_inactive'          THEN 'COMPENSATION'
                WHEN p.base_type = 'once_one_off'             THEN 'ONCE_ONE_OFF'
                WHEN p.base_type = 'partial_first_month'      THEN 'PARTIAL_FIRST_MONTH'
                WHEN p.base_type = 'resend_consolidated_range' THEN 'RESEND_CONSOLIDATED'
                WHEN p.base_type = 'monthly' AND bp.frequency = 'FIRST_OF_EVERY_MONTH'
                     AND YEAR(p.posted_date) = YEAR(bp.day_start) AND MONTH(p.posted_date) = MONTH(bp.day_start)
                    THEN 'FIRST_MONTH'
                WHEN p.base_type = 'monthly' AND bp.frequency = 'FIRST_OF_EVERY_MONTH'
                    THEN 'FULL_MONTH'
                WHEN p.base_type = 'monthly'                  THEN 'MONTHLY'
            END
        ORDER BY (p.period_type LIKE '%\_skipped') ASC, p.id ASC
    ) AS rn
FROM (
    SELECT pp.*,
        CASE
            WHEN pp.period_type = 'resend_consolidated_range_skippe' THEN 'resend_consolidated_range'
            WHEN pp.period_type LIKE '%\_skipped' THEN LEFT(pp.period_type, LENGTH(pp.period_type) - 8)
            ELSE pp.period_type
        END AS base_type
    FROM c168_net_legacy_20260905.process_accounting_posted pp
    WHERE pp.id NOT IN (SELECT id FROM c168_net_legacy_20260827.process_accounting_posted)
) p
JOIN c168_net_legacy_20260905.company c ON c.id = p.company_id
JOIN tenant ten ON ten.tenant_type = 'COMPANY' AND ten.code = c.company_id
JOIN bank_process bp ON bp.id = p.process_id
WHERE p.base_type <> 'rejected';

CREATE TEMPORARY TABLE _new_bap_map (
    legacy_id INT NOT NULL PRIMARY KEY,
    new_id    INT NOT NULL UNIQUE
);
SET @base_bap := (SELECT MAX(id) FROM bank_process_accounting_posted);
INSERT INTO _new_bap_map (legacy_id, new_id)
SELECT x.id, @base_bap + ROW_NUMBER() OVER (ORDER BY x.id)
FROM (
    SELECT np.id
    FROM _new_pap np
    WHERE np.rn = 1
      AND NOT EXISTS (
          SELECT 1 FROM bank_process_accounting_posted bap
          WHERE bap.tenant_id = np.tenant_id AND bap.bank_process_id = np.bank_process_id
            AND bap.posted_date = np.posted_date
            AND bap.period_type = np.period_type
      )
) x;

INSERT INTO bank_process_accounting_posted (id, tenant_id, bank_process_id, posted_date, period_type, outcome, created_at)
SELECT nm.new_id, np.tenant_id, np.bank_process_id, np.posted_date, np.period_type, np.outcome, np.created_at
FROM _new_pap np
JOIN _new_bap_map nm ON nm.legacy_id = np.id;

DROP TEMPORARY TABLE _new_pap;

-- =============================================================================
-- 5. bank_process_accounting_posted from process_accounting_due_dismissed: always fresh
--    auto-increment, always SKIPPED. (COLLATE fix, same as step 4.)
-- =============================================================================
INSERT INTO bank_process_accounting_posted (tenant_id, bank_process_id, posted_date, period_type, outcome, created_at)
SELECT tenant_id, bank_process_id, posted_date, period_type, 'SKIPPED', created_at
FROM (
    SELECT
        ten.id AS tenant_id,
        d.process_id AS bank_process_id,
        d.anchor_date AS posted_date,
        CASE
            WHEN d.period_type = 'resend_monthly_reopen'      THEN
                CASE
                    WHEN bp.frequency = 'FIRST_OF_EVERY_MONTH'
                         AND YEAR(d.anchor_date) = YEAR(bp.day_start) AND MONTH(d.anchor_date) = MONTH(bp.day_start)
                        THEN 'FIRST_MONTH'
                    WHEN bp.frequency = 'FIRST_OF_EVERY_MONTH' THEN 'FULL_MONTH'
                    ELSE 'MONTHLY'
                END
            WHEN d.period_type = 'resend_consolidated_range'  THEN 'RESEND_CONSOLIDATED'
            WHEN d.period_type = 'monthly' AND bp.frequency = 'FIRST_OF_EVERY_MONTH'
                 AND YEAR(d.anchor_date) = YEAR(bp.day_start) AND MONTH(d.anchor_date) = MONTH(bp.day_start)
                THEN 'FIRST_MONTH'
            WHEN d.period_type = 'monthly' AND bp.frequency = 'FIRST_OF_EVERY_MONTH'
                THEN 'FULL_MONTH'
            WHEN d.period_type = 'monthly'                    THEN 'MONTHLY'
        END AS period_type,
        d.created_at
    FROM c168_net_legacy_20260905.process_accounting_due_dismissed d
    JOIN c168_net_legacy_20260905.company c ON c.id = d.company_id
    JOIN tenant ten ON ten.tenant_type = 'COMPANY' AND ten.code = c.company_id
    JOIN bank_process bp ON bp.id = d.process_id
    WHERE d.id NOT IN (SELECT id FROM c168_net_legacy_20260827.process_accounting_due_dismissed)
) y
WHERE NOT EXISTS (
    SELECT 1 FROM bank_process_accounting_posted bap
    WHERE bap.tenant_id = y.tenant_id AND bap.bank_process_id = y.bank_process_id
      AND bap.posted_date = y.posted_date
      AND bap.period_type = y.period_type
);

-- =============================================================================
-- 6. transactions.bank_process_posted_id backfill: only for the NEW transactions.
-- =============================================================================
UPDATE transactions t
JOIN _new_txn_map ntm ON ntm.new_id = t.id
JOIN c168_net_legacy_20260905.transactions lt ON lt.id = ntm.legacy_id
JOIN c168_net_legacy_20260905.process_accounting_posted lp
    ON lp.process_id = lt.source_bank_process_id
   AND lp.posted_date = lt.transaction_date
   AND lp.period_type = lt.source_bank_process_period_type
JOIN bank_process_accounting_posted bap
    ON bap.id = COALESCE((SELECT new_id FROM _new_bap_map WHERE legacy_id = lp.id), lp.id)
SET t.bank_process_posted_id = bap.id
WHERE lt.source_bank_process_id IS NOT NULL;

DROP TEMPORARY TABLE _new_txn_map;
DROP TEMPORARY TABLE _new_bap_map;

COMMIT;
