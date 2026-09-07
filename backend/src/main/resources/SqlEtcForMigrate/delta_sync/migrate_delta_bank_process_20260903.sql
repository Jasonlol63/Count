-- Incremental DATA sync: Bank Process domain (list/CRUD + dropdowns + profit sharing + resend
-- guard) -- companion to migrate_delta_identity_tenant_20260903.sql (run that first, this only
-- needs identity/tenant, not currency/process). Mirrors migrate_data_bank_process_from_legacy.sql,
-- scoped to rows new since the 2026-08-27 baseline.
--
-- id-preservation verified: bank_process baseline_max 690 == count_real max 690 -> preserved.
-- bank_process_accounting_resend_daily_guard baseline_max 281 == count_real max 281 -> preserved.
-- bank_country/bank_option/bank_process_share are NOT id-preserved by the original script (no id in
-- its INSERT column list) -- bank_country/bank_option are already naturally idempotent (INSERT
-- IGNORE against a UNIQUE constraint, safe to just re-run the full statement over ALL legacy rows,
-- old + new); bank_process_share is scoped to only the new bank_process rows' profit_sharing text so
-- it can't duplicate anything already parsed for existing rows.
--
-- Usage:
--   mysql -u root count_real < backend/src/main/resources/SqlEtcForMigrate/migrate_delta_bank_process_20260903.sql

START TRANSACTION;

-- =============================================================================
-- 1. bank_country / bank_option: safe to re-run in full (INSERT IGNORE against UNIQUE constraints
--    already covers idempotency; no need to scope to "new" rows specially).
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
-- 2. bank_process: new rows only, id preserved.
-- =============================================================================
INSERT INTO bank_process (
    id, tenant_id, country_id, bank_option_id, card_owner, card_owner_type,
    day_start, day_end, day_end_monthly_cap_enabled, expired_at_creation, frequency,
    supplier_account_id, supplier_price, customer_account_id, customer_price,
    company_account_id, company_price, contract, insurance_price, sop, remark, status,
    created_by, updated_by, created_at, updated_at
)
SELECT
    bp.id, ten.id, bc.id, bo.id, bp.name, bp.type, bp.day_start, bp.day_end,
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
    bp.card_merchant_id, bp.cost, bp.customer_id, bp.price, bp.profit_account_id, bp.profit,
    bp.contract, bp.insurance, bp.sop, bp.remark,
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
    bp.dts_created, bp.dts_modified
FROM c168_net_legacy_20260827.bank_process bp
JOIN c168_net_legacy_20260827.company c ON c.id = bp.company_id
JOIN tenant ten ON ten.tenant_type = 'COMPANY' AND ten.code = c.company_id
JOIN bank_country bc ON bc.tenant_id = ten.id AND bc.code = bp.country
JOIN bank_option bo ON bo.country_id = bc.id AND bo.name = bp.bank
LEFT JOIN user u1 ON u1.id = bp.created_by
LEFT JOIN owner o1 ON o1.id = bp.created_by_owner_id
LEFT JOIN user u2 ON u2.id = bp.modified_by
LEFT JOIN owner o2 ON o2.id = bp.modified_by_owner_id
WHERE bp.id NOT IN (SELECT id FROM bank_process);

-- =============================================================================
-- 3. bank_process_share: scoped to the new bank_process rows' profit_sharing text only -- can't
--    duplicate anything already parsed for pre-existing rows.
-- =============================================================================
INSERT INTO bank_process_share (bank_process_id, account_id, amount, sort_order)
SELECT
    bp.id, a.id,
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
WHERE bp.profit_sharing IS NOT NULL AND bp.profit_sharing != ''
  AND bp.id NOT IN (SELECT id FROM c168_net_legacy_20260827_baseline.bank_process)
  AND NOT EXISTS (SELECT 1 FROM bank_process_share s WHERE s.bank_process_id = bp.id);

-- =============================================================================
-- 4. bank_process_resend_daily_guard: new rows only, id preserved.
-- =============================================================================
-- Orphan guard added here (not present in the original script, which never needed it -- that
-- one-off run saw every bank_process_id resolve because bank_process itself was migrated
-- unconditionally in full first). Caught by a FK error on first dry-run: one new guard row
-- references a bank_process_id that no longer exists in the current legacy `bank_process` table at
-- all (deleted from the old system between the baseline and current snapshots) -- silently excluded
-- here, same "orphan, not fabricated" treatment documented throughout this migration for
-- companies/processes deleted before any archival existed.
INSERT INTO bank_process_resend_daily_guard (
    id, tenant_id, bank_process_id, resend_day_start, guard_date, created_at
)
SELECT g.id, ten.id, g.bank_process_id, g.resend_day_start, g.guard_date, g.created_at
FROM c168_net_legacy_20260827.bank_process_accounting_resend_daily_guard g
JOIN c168_net_legacy_20260827.company c ON c.id = g.company_id
JOIN tenant ten ON ten.tenant_type = 'COMPANY' AND ten.code = c.company_id
JOIN bank_process bp ON bp.id = g.bank_process_id
WHERE g.id NOT IN (SELECT id FROM bank_process_resend_daily_guard);

COMMIT;
