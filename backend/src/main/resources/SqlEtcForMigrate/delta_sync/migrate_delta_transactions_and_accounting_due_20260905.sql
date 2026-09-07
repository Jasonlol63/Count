-- Incremental DATA sync: Transactions/RATE domain + Bank Process Accounting Due ledger -- third
-- round (2026-09-05). Companion to migrate_delta_identity_tenant_20260905.sql /
-- migrate_delta_currency_domain_20260905.sql / migrate_delta_bank_process_20260905.sql (run those
-- first). Mirrors migrate_delta_transactions_and_accounting_due_20260903.sql one round later.
--
-- `transactions` and `bank_process_accounting_posted` are still the two tables with REAL organic
-- growth in count_real (current max ids: transactions 151377, bank_process_accounting_posted 3207 --
-- both far above any legacy id in this dump, so fresh ids required, same as every prior round).
--
-- "Already synced" reference throughout = `c168_net_legacy_20260827` (this round's prior-state
-- snapshot, holding the 2026-09-03 dump's content -- NOT the original frozen 2026-08-27
-- `_baseline`, which would re-select the entire 2026-09-03 batch as if new -- see session notes on
-- why that distinction matters).
--
-- EXPLICITLY OUT OF SCOPE this round (user decision, 2026-09-05): 81 legacy transaction ids and 88
-- legacy accounting-posted/dismissed ids that existed as of the 2026-09-03 state have since been
-- reversed/deleted upstream (verified: all 81 transactions now appear in this dump's own
-- `transactions_deleted`). Because they're simply absent from `c168_net_legacy_20260905` (the source
-- below), they are naturally excluded from every INSERT here -- this script only adds genuinely new
-- rows, it does NOT retroactively remove or soft-delete anything already migrated in an earlier round.
--
-- Usage:
--   mysql -u root count_real < backend/src/main/resources/SqlEtcForMigrate/migrate_delta_transactions_and_accounting_due_20260905.sql

START TRANSACTION;

-- =============================================================================
-- 1. transactions: fresh-id mapping for new rows.
-- =============================================================================
CREATE TEMPORARY TABLE _map_currency (
    old_currency_id  INT NOT NULL PRIMARY KEY,
    survivor_id      INT NOT NULL
);
INSERT INTO _map_currency (old_currency_id, survivor_id)
SELECT cu.id, s.id
FROM c168_net_legacy_20260905.currency cu
JOIN (
    SELECT id, company_id, code,
           ROW_NUMBER() OVER (PARTITION BY company_id, code ORDER BY (sync_source = 'subsidiary'), id) AS rn
    FROM c168_net_legacy_20260905.currency
) s ON s.company_id = cu.company_id AND s.code = cu.code
WHERE s.rn = 1;

CREATE TEMPORARY TABLE _new_txn_map (
    legacy_id INT NOT NULL PRIMARY KEY,
    new_id    INT NOT NULL UNIQUE
);
SET @base_txn := (SELECT MAX(id) FROM transactions);
INSERT INTO _new_txn_map (legacy_id, new_id)
SELECT x.legacy_id, @base_txn + x.rn
FROM (
    SELECT t.id AS legacy_id, ROW_NUMBER() OVER (ORDER BY t.id) AS rn
    FROM c168_net_legacy_20260905.transactions t
    JOIN c168_net_legacy_20260905.company c ON c.id = t.company_id
    JOIN tenant ten ON ten.tenant_type = 'COMPANY' AND ten.code = c.company_id
    JOIN _map_currency mc ON mc.old_currency_id = t.currency_id
    WHERE t.id NOT IN (SELECT id FROM c168_net_legacy_20260827.transactions)
) x;

INSERT INTO transactions (
    id, tenant_id, transaction_type, account_id, from_account_id, currency_id, amount,
    transaction_date, description, remark, created_by, approval_status, approved_by, approved_at,
    created_at, updated_at
)
SELECT
    nm.new_id, ten.id, t.transaction_type, t.account_id, t.from_account_id, mc.survivor_id, t.amount,
    t.transaction_date, t.description, t.sms,
    COALESCE(u1.login_id, o1.owner_code), t.approval_status,
    COALESCE(u2.login_id, o2.owner_code), t.approved_at, t.created_at, t.updated_at
FROM c168_net_legacy_20260905.transactions t
JOIN _new_txn_map nm ON nm.legacy_id = t.id
JOIN c168_net_legacy_20260905.company c ON c.id = t.company_id
JOIN tenant ten ON ten.tenant_type = 'COMPANY' AND ten.code = c.company_id
JOIN _map_currency mc ON mc.old_currency_id = t.currency_id
LEFT JOIN user u1 ON u1.id = t.created_by
LEFT JOIN owner o1 ON o1.id = t.created_by_owner
LEFT JOIN user u2 ON u2.id = t.approved_by
LEFT JOIN owner o2 ON o2.id = t.approved_by_owner;

-- =============================================================================
-- 2. transactions_rate: new rate groups only. (0 expected this round, verified.)
-- =============================================================================
CREATE TEMPORARY TABLE _resolve_rate (
    rate_group_id VARCHAR(50) NOT NULL PRIMARY KEY,
    leg1_id       INT NOT NULL,
    leg2_id       INT NOT NULL
);
INSERT INTO _resolve_rate (rate_group_id, leg1_id, leg2_id)
SELECT tr.rate_group_id, tr.transaction_id, trd.transaction_id
FROM c168_net_legacy_20260905.transactions_rate tr
JOIN c168_net_legacy_20260905.transactions_rate_details trd
    ON trd.rate_group_id = tr.rate_group_id AND trd.record_type = 'transfer_to'
WHERE tr.rate_group_id NOT IN (SELECT rate_group_id FROM c168_net_legacy_20260827.transactions_rate);

INSERT INTO transactions_rate (
    tenant_id, rate_group_id, leg1_transaction_id, leg2_transaction_id, exchange_rate,
    currency_from_id, amount_from, currency_to_id, amount_to,
    middleman_account_id, middleman_rate, middleman_amount, created_at, updated_at
)
SELECT
    ten.id, tr.rate_group_id,
    COALESCE(nm1.new_id, r.leg1_id), COALESCE(nm2.new_id, r.leg2_id),
    tr.exchange_rate, mcf.survivor_id, tr.rate_from_amount, mct.survivor_id, tr.rate_to_amount,
    tr.rate_middleman_account_id, tr.rate_middleman_rate, tr.rate_middleman_amount,
    tr.created_at, tr.updated_at
FROM c168_net_legacy_20260905.transactions_rate tr
JOIN _resolve_rate r ON r.rate_group_id = tr.rate_group_id
JOIN c168_net_legacy_20260905.company c ON c.id = tr.company_id
JOIN tenant ten ON ten.tenant_type = 'COMPANY' AND ten.code = c.company_id
JOIN _map_currency mcf ON mcf.old_currency_id = tr.rate_from_currency_id
JOIN _map_currency mct ON mct.old_currency_id = tr.rate_to_currency_id
LEFT JOIN _new_txn_map nm1 ON nm1.legacy_id = r.leg1_id
LEFT JOIN _new_txn_map nm2 ON nm2.legacy_id = r.leg2_id
WHERE NOT EXISTS (SELECT 1 FROM transactions_rate x WHERE x.rate_group_id = tr.rate_group_id);

UPDATE transactions t
JOIN _resolve_rate r ON t.id = COALESCE(
    (SELECT new_id FROM _new_txn_map WHERE legacy_id = r.leg1_id), r.leg1_id
) OR t.id = COALESCE(
    (SELECT new_id FROM _new_txn_map WHERE legacy_id = r.leg2_id), r.leg2_id
)
SET t.rate_group_id = r.rate_group_id
WHERE t.rate_group_id IS NULL;

DROP TEMPORARY TABLE _resolve_rate;

-- =============================================================================
-- 3. transactions_deleted: fresh surrogate key -- scoped to legacy rows new since the 2026-09-03
--    state. (111 expected this round.)
-- =============================================================================
INSERT INTO transactions_deleted (
    tenant_id, transaction_id, transaction_type, account_id, from_account_id, currency_id, amount,
    transaction_date, description, remark, created_by, created_at, deleted_by, deleted_at
)
SELECT
    ten.id, td.transaction_id, td.transaction_type, td.account_id, td.from_account_id,
    mc.survivor_id, td.amount, td.transaction_date, td.description, td.sms,
    COALESCE(u1.login_id, o1.owner_code), td.created_at,
    COALESCE(u2.login_id, o2.owner_code), td.deleted_at
FROM c168_net_legacy_20260905.transactions_deleted td
JOIN c168_net_legacy_20260905.company c ON c.id = td.company_id
JOIN tenant ten ON ten.tenant_type = 'COMPANY' AND ten.code = c.company_id
JOIN account a ON a.id = td.account_id
LEFT JOIN _map_currency mc ON mc.old_currency_id = td.currency_id
LEFT JOIN user u1 ON u1.id = td.created_by
LEFT JOIN owner o1 ON o1.id = td.created_by_owner
LEFT JOIN user u2 ON u2.id = td.deleted_by_user_id
LEFT JOIN owner o2 ON o2.id = td.deleted_by_owner_id
WHERE td.transaction_type IN ('WIN','LOSE','PAYMENT','CONTRA','CLAIM','RATE','CLEAR','ADJUSTMENT')
  AND td.id NOT IN (SELECT id FROM c168_net_legacy_20260827.transactions_deleted)
  AND NOT EXISTS (
      SELECT 1 FROM transactions_deleted x
      WHERE x.tenant_id = ten.id AND x.transaction_id = td.transaction_id AND x.deleted_at = td.deleted_at
  );

-- =============================================================================
-- 4. bank_process_accounting_posted from process_accounting_posted: fresh-id mapping.
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
--    auto-increment, always SKIPPED.
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

DROP TEMPORARY TABLE _map_currency;
DROP TEMPORARY TABLE _new_txn_map;
DROP TEMPORARY TABLE _new_bap_map;

COMMIT;
