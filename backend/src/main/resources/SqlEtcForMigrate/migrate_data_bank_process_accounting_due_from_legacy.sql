-- One-off DATA migration: Bank Process Accounting Due ledger + the one still-open Resend schedule +
-- the transactions.bank_process_posted_id backfill. Follow-up to
-- migrate_data_bank_process_from_legacy.sql (§13.2 in MIGRATION_LOG.md) -- the pieces deferred there
-- because their legacy period_type values had no confirmed mapping. Confirmed by reading the legacy
-- PHP source at C:\Users\User\OneDrive\Desktop\count168test before writing this (see decisions below).
--
-- Source: staging DB holding the raw 2026-08-27 c168.net mysqldump (`c168_net_legacy_20260827`).
-- Target: count_real -- requires migrate_data_bank_process_from_legacy.sql already run (bank_process
--         ids are reused as-is) and migrate_data_transactions_from_legacy.sql already run
--         (transactions ids are reused as-is for the final backfill step).
--
-- Confirmed mappings (read from C:\Users\User\OneDrive\Desktop\count168test):
--   - `manual_inactive` -> COMPENSATION. docs/bankprocess-accounting-due-lifecycle-rules.md §2 footnote:
--     "1+1/1+2/1+3 合同在设为 Official/E-Invoice 时的一次性违约金入账（manual_inactive）逻辑保留不变"; PHP
--     process_post_to_transaction_api.php builds this exact 1+N compensation multiplier under the
--     literal string 'manual_inactive'. The current Spring Boot BankAccountingDueServiceImpl already
--     has a full, mature COMPENSATION implementation (resolveOnePlusCompensationDue /
--     postCompensationPeriod / settleCompensationSlot) that is the direct, already-built successor --
--     confirms this isn't a guess.
--   - `resend_monthly_reopen` (only ever appears in process_accounting_due_dismissed -- it never
--     reaches process_accounting_posted because dismiss_accounting_due_api.php normalizes it to plain
--     'monthly' before any DB write: `if ($periodType === 'resend_monthly_reopen') { $periodType =
--     'monthly'; }`) -> maps to MONTHLY (or FIRST_MONTH/FULL_MONTH per the same disambiguation as
--     plain 'monthly' below), outcome SKIPPED.
--   - `bank_process.resend_schedule_day_start/day_end/frequency` ("currently open" Resend): the 54
--     "not yet posted" rows in bank_process_maintenance_resend_pending are NOT the open-schedule
--     signal -- reading maintenance_accounting_resend_lib.php showed that table is a resend->posting
--     audit/cleanup index (tracks which already-posted rows came from a resend batch, used only for
--     Maintenance delete), unrelated to "is there an open schedule right now". The real signal is
--     `bank_process.accounting_resend_relax_created_floor` (a boolean gate) + the paired
--     accounting_resend_schedule_day_start/day_end/frequency columns (or the newer
--     accounting_resend_open_anchors JSON, unused in this data -- 0 rows). Checked: exactly ONE
--     bank_process (id 420) has relax_created_floor=1, with a clean, single, unambiguous schedule
--     (2026-05-31 .. 2026-06-15, 1st_of_every_month). No conflict, no guessing needed.
--   - `rejected` (1 row in process_accounting_posted): not a recognized Accounting Due period_type
--     anywhere in the PHP source (the only 'rejected' status found in the codebase belongs to the
--     unrelated company_auto_renew_request domain) -- treated as legacy garbage, excluded.
--
-- Other decisions:
--   - Legacy 'monthly' is ambiguous: for a bank_process with frequency=MONTHLY it means the new
--     schema's MONTHLY; for frequency=FIRST_OF_EVERY_MONTH it means either FIRST_MONTH (posted_date
--     falls in day_start's own month) or FULL_MONTH (any later month) --
--     docs/frontend-springboot-migration.md §"1st of every month": "dayStart 为当月1日：FIRST_MONTH...
--     中间完整月份：FULL_MONTH". Disambiguated per-row by comparing posted_date's year-month against
--     bank_process.day_start's year-month (uses the already-migrated count_real.bank_process row, not
--     the legacy one, since frequency there is already the new enum value).
--   - Legacy `_skipped` suffix stripping bug found and fixed while writing this: a naive
--     REPLACE(period_type, '_skippe', '') corrupts the correctly-spelled 'day_end_tail_skipped' (the
--     7-char needle '_skippe' is also a substring of the correctly-spelled 8-char '_skipped', so the
--     replace fires on both and leaves a stray trailing 'd'). Fixed by handling the one genuinely
--     misspelled value ('resend_consolidated_range_skippe', 4 rows, missing the final 'd') as its own
--     explicit case, and stripping the correctly-spelled '_skipped' suffix by length rather than by a
--     naive substring replace.
--   - Two source tables (process_accounting_posted's *_skipped rows and process_accounting_due_
--     dismissed) overlap heavily: 10 rows in due_dismissed have a matching (bank_process_id,date)
--     counterpart already in process_accounting_posted, and every single one of those pairs
--     normalizes to the SAME final period_type -- due_dismissed is largely a redundant audit re-entry
--     of something process_accounting_posted's own *_skipped rows already capture, not a distinct
--     third data source. Since the new schema's UNIQUE(tenant_id, bank_process_id, posted_date,
--     period_type) can't hold both, §3 below only inserts a due_dismissed row when nothing from §2
--     already claimed that slot. Separately (unrelated to that overlap), one bank_process (id 420)
--     has a genuine POSTED row and a later SKIPPED row for the exact same (date, period_type) within
--     process_accounting_posted itself (id 1547 day_end_tail POSTED 2026-06-01, id 1679
--     day_end_tail_skipped 2026-07-24) -- POSTED wins (it has real transactions tied to it; the later
--     SKIPPED entry looks like redundant re-processing, not a real reversal) via the ROW_NUMBER dedupe
--     in §2.
--   - Neither legacy table (process_accounting_posted / process_accounting_due_dismissed) records who
--     posted/skipped/dismissed the period -- new `created_by` is left NULL for every migrated row, not
--     fabricated.
--   - id preserved 1:1 for rows sourced from process_accounting_posted (matches the id-preservation
--     convention used everywhere else in this migration, and lets the final backfill step below join
--     cleanly). Rows sourced from process_accounting_due_dismissed get a fresh auto-increment id --
--     its legacy id space is a separate, unrelated sequence that could collide with
--     process_accounting_posted's, and nothing else references process_accounting_due_dismissed.id.
--   - Same orphan reality as everywhere else in this migration: process_accounting_posted has 921
--     legacy rows but only 246 resolve to a still-existing company AND bank_process (the rest
--     reference companies/processes deleted long before any archival existed -- see
--     MIGRATION_LOG.md §12 for the same pattern in transactions_deleted). process_accounting_due_
--     dismissed: 25 legacy rows, 20 resolve.
--
-- Idempotency: NOT idempotent -- intended for a single run against an empty bank_process_accounting_
-- posted table, with transactions.bank_process_posted_id still all NULL.
--
-- Usage:
--   mysql -u root count_real < backend/src/main/resources/SqlEtcForMigrate/migrate_data_bank_process_accounting_due_from_legacy.sql

-- =============================================================================
-- 1. The one still-open Resend schedule (bank_process.id 420 in both legacy and count_real -- id
--    preserved 1:1 by migrate_data_bank_process_from_legacy.sql).
-- =============================================================================
UPDATE bank_process
SET resend_schedule_day_start = '2026-05-31',
    resend_schedule_day_end   = '2026-06-15',
    resend_schedule_frequency = 'FIRST_OF_EVERY_MONTH'
WHERE id = 420;

-- A fresh run against a newer legacy snapshot (2026-09-03) surfaced 2 more bank_process rows
-- (694, 701) with accounting_resend_relax_created_floor=1 -- same open-Resend-schedule situation as
-- id 420 above. Both have a single, clean, unambiguous schedule (2026-08-29..2027-02-28,
-- 1st_of_every_month), no conflict. Added here so a future re-run reaches the correct end state
-- without a manual step; harmless no-op against a snapshot where these 2 don't have the flag set.
UPDATE bank_process
SET resend_schedule_day_start = '2026-08-29',
    resend_schedule_day_end   = '2027-02-28',
    resend_schedule_frequency = 'FIRST_OF_EVERY_MONTH'
WHERE id IN (694, 701) AND accounting_resend_relax_created_floor = 1;

-- =============================================================================
-- 2. bank_process_accounting_posted from process_accounting_posted (POSTED / SKIPPED via the legacy
--    *_skipped period_type suffix). id preserved 1:1. Deduped per (tenant, bank_process, posted_date,
--    final period_type): POSTED wins over SKIPPED, lowest id breaks remaining ties (see header notes
--    on the bank_process 420 day_end_tail case).
-- =============================================================================
INSERT INTO bank_process_accounting_posted (
    id, tenant_id, bank_process_id, posted_date, period_type, outcome, created_at
)
SELECT id, tenant_id, bank_process_id, posted_date, period_type, outcome, created_at
FROM (
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
        FROM c168_net_legacy_20260827.process_accounting_posted pp
    ) p
    JOIN c168_net_legacy_20260827.company c ON c.id = p.company_id
    JOIN tenant ten ON ten.tenant_type = 'COMPANY' AND ten.code = c.company_id
    JOIN bank_process bp ON bp.id = p.process_id
    WHERE p.base_type <> 'rejected'
) x
WHERE rn = 1;

-- =============================================================================
-- 3. bank_process_accounting_posted from process_accounting_due_dismissed (always outcome SKIPPED;
--    fresh auto-increment id). resend_monthly_reopen normalizes to MONTHLY (see header notes). Only
--    inserted when §2 didn't already claim that (tenant, bank_process, posted_date, period_type) slot
--    (see header notes on the process_accounting_posted / due_dismissed overlap).
-- =============================================================================
INSERT INTO bank_process_accounting_posted (
    tenant_id, bank_process_id, posted_date, period_type, outcome, created_at
)
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
    FROM c168_net_legacy_20260827.process_accounting_due_dismissed d
    JOIN c168_net_legacy_20260827.company c ON c.id = d.company_id
    JOIN tenant ten ON ten.tenant_type = 'COMPANY' AND ten.code = c.company_id
    JOIN bank_process bp ON bp.id = d.process_id
) y
WHERE NOT EXISTS (
    SELECT 1 FROM bank_process_accounting_posted bap
    WHERE bap.tenant_id = y.tenant_id
      AND bap.bank_process_id = y.bank_process_id
      AND bap.posted_date = y.posted_date
      AND bap.period_type = y.period_type
);

-- =============================================================================
-- 4. transactions.bank_process_posted_id backfill. Legacy transactions.source_bank_process_id +
--    source_bank_process_period_type + transaction_date identify which process_accounting_posted
--    row a transaction line came from -- posted_date alone is NOT enough (verified: 32 transactions
--    have 2 candidate posted rows on the same date for the same process, disambiguated only by also
--    matching the legacy (pre-normalization) period_type string). Only POSTED rows (id preserved 1:1
--    above, joined by the SAME legacy id) are relevant -- SKIPPED/dismissed periods never generated
--    transactions.
-- =============================================================================
UPDATE transactions t
JOIN c168_net_legacy_20260827.transactions lt ON lt.id = t.id
JOIN c168_net_legacy_20260827.process_accounting_posted lp
    ON lp.process_id = lt.source_bank_process_id
   AND lp.posted_date = lt.transaction_date
   AND lp.period_type = lt.source_bank_process_period_type
JOIN bank_process_accounting_posted bap ON bap.id = lp.id
SET t.bank_process_posted_id = bap.id
WHERE lt.source_bank_process_id IS NOT NULL;
