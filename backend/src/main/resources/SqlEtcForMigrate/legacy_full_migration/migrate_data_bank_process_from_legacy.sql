-- One-off DATA migration: Bank Process domain (list/CRUD + country/bank dropdowns + profit sharing +
-- same-day Resend lock), legacy PHP DB -> Spring Boot tenant model.
--
-- Source: staging DB holding the raw 2026-08-27 c168.net mysqldump, imported as-is
--         (`c168_net_legacy_20260827` below -- adjust if your staging DB name differs).
-- Target: count_real -- requires identity/tenant already migrated (maps by tenant.code).
--
-- Scope this script covers: bank_country, bank_option, bank_process (core CRUD fields only --
-- see NOT covered below), bank_process_share, bank_process_resend_daily_guard.
--
-- NOT covered by this script (deliberately deferred, see MIGRATION_LOG.md for why):
--   - bank_process.resend_schedule_day_start/day_end/frequency (legacy
--     bank_process_maintenance_resend_pending "still open" rows) -- legacy data has multiple
--     simultaneously-open rows for the same bank_process_id in a few cases, which the new schema's
--     "at most one open make-up per process" model can't represent as-is; needs a product decision
--     on conflict resolution, not a guess.
--   - bank_process_accounting_posted / the transactions.bank_process_posted_id backfill -- legacy
--     `process_accounting_posted.period_type` has two values ('manual_inactive', and
--     `process_accounting_due_dismissed`'s 'resend_monthly_reopen') with no confirmed mapping to the
--     new period_type ENUM, and disambiguating legacy 'monthly' into the new schema's FIRST_MONTH vs
--     FULL_MONTH (for FIRST_OF_EVERY_MONTH-frequency processes) requires comparing posted_date's
--     month against bank_process.day_start's month -- doable, but bundled with the above unresolved
--     mapping, so left for a follow-up script once that's settled.
--
-- Known upstream decisions baked into this script:
--   - bank_country / bank_option are a NEW per-tenant dropdown-options model with no 1:1 legacy
--     table. Legacy actually has TWO generations of the same feature living side by side:
--     `country_bank` (188 rows, own id, no sort_order) + `company_countries` (36 rows, country-only)
--     look like an older design; `company_selected_countries` (50 rows) + `company_selected_banks`
--     (130 rows, composite PK + sort_order) look like the current one. Rather than pick a "winner",
--     this script UNIONs every legacy source (all four selection tables + bank_process itself, so a
--     bank_process row can never reference a country/bank that didn't make it into the dropdown
--     list) and lets the new UNIQUE(tenant_id, code) / UNIQUE(country_id, name) constraints collapse
--     duplicates. sort_order is not carried over (no destination column on the new tables).
--   - bank_process.status folds in legacy issue_flag: legacy has a separate 3-value status
--     (active/inactive/waiting) AND a separate issue_flag (block/official, 10 rows total) that the
--     new schema merges into one 6-value status enum. issue_flag='block'/'official' wins over the
--     base status when both are set (verified: wherever issue_flag is set, it's the more specific,
--     more current signal -- e.g. a row can be status=active + issue_flag=block, meaning "was active,
--     now blocked").
--   - card_merchant_id -> supplier_account_id, customer_id -> customer_account_id,
--     profit_account_id -> company_account_id, cost/price/profit -> supplier_price/customer_price/
--     company_price: matched by legacy column semantics (Buy Price=Cost=Supplier, Sell Price=Price=
--     Customer, Profit=company's own margin) cross-checked against
--     docs/frontend-springboot-migration.md's "Buy Price -> Supplier / Sell Price -> Customer /
--     Profit -> Company" convention for Accounting Due posting -- consistent naming, not a guess.
--   - expired_at_creation recomputed from its own documented formula (schema.sql comment: "1 = day_end
--     's month was already before the creation month"), not read from any legacy column (legacy has
--     no equivalent flag -- this is a new derived-at-insert business rule).
--   - accounting_reactivated_floor_ymd / issue_flag_locked_end_ymd / accounting_resend_relax_created_
--     floor / accounting_resend_open_anchors: checked usage before dropping -- 1, 0, 1, and 0 non-
--     default rows respectively out of 185. Effectively unused, and none has a destination column in
--     the new schema. Not migrated.
--   - profit_sharing (free-text "CODE [Name] - amount, CODE [Name] - amount") parsed into
--     bank_process_share rows. Verified format across all 40 non-empty rows: at most 2 comma-
--     separated entries, each "<code> ... - <amount>" with no hyphens inside any code, so
--     SUBSTRING_INDEX on ' ' (first token) / '-' (last token) reliably extracts code / amount. Code
--     resolved to account.id via account_tenant_access scoped to the SAME tenant as the bank_process
--     row (account_id is tenant-unique, not global).
--   - created_by/updated_by: legacy already has an explicit created_by_type / modified_by_type
--     ('user'/'owner') discriminator column (unlike the Transactions domain's two-ambiguous-columns
--     pattern) -- resolved unambiguously via that flag, no precedence judgment call needed here.
--
-- Idempotency: NOT idempotent -- intended for a single run against empty target tables.
--
-- Usage:
--   mysql -u root count_real < backend/src/main/resources/SqlEtcForMigrate/migrate_data_bank_process_from_legacy.sql

-- =============================================================================
-- 1. bank_country / bank_option: union every legacy selection-list source + bank_process itself,
--    let the UNIQUE constraints dedupe.
-- =============================================================================
INSERT IGNORE INTO bank_country (tenant_id, code, created_at)
SELECT DISTINCT ten.id, x.country, NOW()
FROM (
    SELECT company_id, country FROM c168_net_legacy_20260827.country_bank
    UNION
    SELECT company_id, country FROM c168_net_legacy_20260827.company_countries
    UNION
    SELECT company_id, country FROM c168_net_legacy_20260827.company_selected_countries
    UNION
    SELECT company_id, country FROM c168_net_legacy_20260827.company_selected_banks
    UNION
    SELECT company_id, country FROM c168_net_legacy_20260827.bank_process
) x
JOIN c168_net_legacy_20260827.company c ON c.id = x.company_id
JOIN tenant ten ON ten.tenant_type = 'COMPANY' AND ten.code = c.company_id;

INSERT IGNORE INTO bank_option (tenant_id, country_id, name, created_at)
SELECT DISTINCT ten.id, bc.id, x.bank, NOW()
FROM (
    SELECT company_id, country, bank FROM c168_net_legacy_20260827.country_bank
    UNION
    SELECT company_id, country, bank FROM c168_net_legacy_20260827.company_selected_banks
    UNION
    SELECT company_id, country, bank FROM c168_net_legacy_20260827.bank_process
) x
JOIN c168_net_legacy_20260827.company c ON c.id = x.company_id
JOIN tenant ten ON ten.tenant_type = 'COMPANY' AND ten.code = c.company_id
JOIN bank_country bc ON bc.tenant_id = ten.id AND bc.code = x.country;

-- =============================================================================
-- 2. bank_process (1:1, id preserved -- target table empty; bank_process_share/
--    bank_process_resend_daily_guard below reference these ids).
-- =============================================================================
INSERT INTO bank_process (
    id, tenant_id, country_id, bank_option_id, card_owner, card_owner_type,
    day_start, day_end, day_end_monthly_cap_enabled, expired_at_creation, frequency,
    supplier_account_id, supplier_price, customer_account_id, customer_price,
    company_account_id, company_price, contract, insurance_price, sop, remark, status,
    created_by, updated_by, created_at, updated_at
)
SELECT
    bp.id,
    ten.id,
    bc.id,
    bo.id,
    bp.name,
    bp.type,
    bp.day_start,
    bp.day_end,
    bp.day_end_monthly_cap_enabled,
    CASE
        WHEN bp.day_start_frequency IN ('1st_of_every_month', 'monthly')
             AND bp.day_end IS NOT NULL
             AND LAST_DAY(bp.day_end) < DATE_FORMAT(bp.dts_created, '%Y-%m-01')
        THEN 1 ELSE 0
    END,
    CASE bp.day_start_frequency
        WHEN '1st_of_every_month' THEN 'FIRST_OF_EVERY_MONTH'
        WHEN 'monthly'            THEN 'MONTHLY'
        WHEN 'once'               THEN 'ONCE'
        WHEN 'day'                THEN 'DAY'
        WHEN 'week'                THEN 'WEEK'
        ELSE 'FIRST_OF_EVERY_MONTH'
    END,
    bp.card_merchant_id,
    bp.cost,
    bp.customer_id,
    bp.price,
    bp.profit_account_id,
    bp.profit,
    bp.contract,
    bp.insurance,
    bp.sop,
    bp.remark,
    CASE
        WHEN bp.issue_flag = 'block'    THEN 'BLOCK'
        WHEN bp.issue_flag = 'official' THEN 'OFFICIAL'
        WHEN bp.status = 'active'       THEN 'ACTIVE'
        WHEN bp.status = 'inactive'     THEN 'INACTIVE'
        WHEN bp.status = 'waiting'      THEN 'WAITING'
        ELSE 'ACTIVE'
    END,
    CASE WHEN bp.created_by_type = 'owner' THEN o1.owner_code ELSE u1.login_id END,
    CASE
        WHEN bp.modified_by IS NULL AND bp.modified_by_owner_id IS NULL THEN NULL
        WHEN bp.modified_by_type = 'owner' THEN o2.owner_code
        ELSE u2.login_id
    END,
    bp.dts_created,
    bp.dts_modified
FROM c168_net_legacy_20260827.bank_process bp
JOIN c168_net_legacy_20260827.company c ON c.id = bp.company_id
JOIN tenant ten ON ten.tenant_type = 'COMPANY' AND ten.code = c.company_id
JOIN bank_country bc ON bc.tenant_id = ten.id AND bc.code = bp.country
JOIN bank_option bo ON bo.country_id = bc.id AND bo.name = bp.bank
LEFT JOIN user u1 ON u1.id = bp.created_by
LEFT JOIN owner o1 ON o1.id = bp.created_by_owner_id
LEFT JOIN user u2 ON u2.id = bp.modified_by
LEFT JOIN owner o2 ON o2.id = bp.modified_by_owner_id;

-- =============================================================================
-- 3. bank_process_share: parse "CODE [Name] - amount, CODE [Name] - amount" free text.
--    Verified max 2 comma-separated parts across all 40 non-empty rows.
-- =============================================================================
INSERT INTO bank_process_share (bank_process_id, account_id, amount, sort_order)
SELECT
    bp.id,
    a.id,
    CAST(TRIM(SUBSTRING_INDEX(parts.part, '-', -1)) AS DECIMAL(25, 8)),
    parts.i
FROM c168_net_legacy_20260827.bank_process bp
JOIN c168_net_legacy_20260827.company c ON c.id = bp.company_id
JOIN tenant ten ON ten.tenant_type = 'COMPANY' AND ten.code = c.company_id
CROSS JOIN (SELECT 0 AS i UNION ALL SELECT 1) parts_idx
JOIN (
    SELECT
        bp2.id AS bp_id,
        parts_idx2.i,
        TRIM(SUBSTRING_INDEX(SUBSTRING_INDEX(bp2.profit_sharing, ',', parts_idx2.i + 1), ',', -1)) AS part
    FROM c168_net_legacy_20260827.bank_process bp2
    CROSS JOIN (SELECT 0 AS i UNION ALL SELECT 1) parts_idx2
    WHERE bp2.profit_sharing IS NOT NULL AND bp2.profit_sharing != ''
      AND parts_idx2.i < (LENGTH(bp2.profit_sharing) - LENGTH(REPLACE(bp2.profit_sharing, ',', '')) + 1)
) parts ON parts.bp_id = bp.id AND parts.i = parts_idx.i
JOIN account_tenant_access ata ON ata.tenant_id = ten.id
JOIN account a ON a.id = ata.account_id AND a.account_id = TRIM(SUBSTRING_INDEX(parts.part, ' ', 1))
WHERE bp.profit_sharing IS NOT NULL AND bp.profit_sharing != '';

-- =============================================================================
-- 4. bank_process_resend_daily_guard (1:1, id preserved -- target table empty).
-- =============================================================================
INSERT INTO bank_process_resend_daily_guard (
    id, tenant_id, bank_process_id, resend_day_start, guard_date, created_at
)
SELECT
    g.id,
    ten.id,
    g.bank_process_id,
    g.resend_day_start,
    g.guard_date,
    g.created_at
FROM c168_net_legacy_20260827.bank_process_accounting_resend_daily_guard g
JOIN c168_net_legacy_20260827.company c ON c.id = g.company_id
JOIN tenant ten ON ten.tenant_type = 'COMPANY' AND ten.code = c.company_id
-- Orphan guard added 2026-09-03: a fresh run against a newer legacy snapshot surfaced 2 guard rows
-- whose bank_process_id no longer exists in the current legacy `bank_process` table at all (deleted
-- from the old system after this script was first written) -- FK error without this join. Silently
-- excluded, same "orphan, not fabricated" treatment used throughout this migration for
-- companies/processes deleted before any archival existed.
JOIN c168_net_legacy_20260827.bank_process bp ON bp.id = g.bank_process_id;
