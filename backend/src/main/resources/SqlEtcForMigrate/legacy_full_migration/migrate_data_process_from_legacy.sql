-- One-off DATA migration (not DDL): Process domain (GAME category only -- BANK-category processes
-- come from legacy `bank_process` in the Bank Process domain, a separate script), legacy PHP DB ->
-- Spring Boot tenant model. Depends on migrate_data_identity_tenant_from_legacy.sql and
-- migrate_data_currency_domain_ownership_from_legacy.sql having already run (needs tenant/currency).
--
-- Scope: process_description (from `description`), process (from `process`), process_description_link,
--        process_day, process_submitted (from `submitted_processes`).
--
-- =============================================================================
-- REVISION NOTE (superseded an earlier merge-based approach): legacy `process` has ~155 groups of
-- rows sharing the same (company_id, process_id) business code, each with a DIFFERENT description_id
-- (e.g. company 300's `WCC` appears 21 times). An earlier version of this script collapsed each group
-- into one survivor `process` row (picking the most-recently-modified row's other fields) and fanned
-- the group's distinct description_id values out into process_description_link.
--
-- That was reverted after checking one concrete group in detail (95@EA42 at company 123 / tenant 95):
-- all 3 rows had ACTUAL, ongoing Data Capture submissions running in parallel for months (11/10/4
-- submissions respectively, overlapping March-August), each with a genuinely different
-- `replace_word_from` text-matching rule (different report sections: Sport / Live Casino / E-Games)
-- and a structurally different commission formula actually executed at capture time (2-leg 6% vs.
-- 3-leg 12%/1%/11% split). This is NOT the old system working around a missing many-to-many -- it is
-- three deliberately distinct configurations that happen to share one business code. Merging would
-- have silently dropped two of the three live `remove_word`/`replace_word_from`/`replace_word_to`
-- configs going forward (historical amounts were not actually at risk -- data_capture_details stores
-- its own formula/amount snapshot independent of `process` -- but the live parsing config for future
-- captures would have been).
--
-- Current approach (confirmed with user): NO merging. Every legacy `process` row becomes its own
-- live `process` row, 1:1, exactly like the old admin UI showed them (one row per description). ID is
-- preserved as-is (target table is empty), so `process_day` / `submitted_processes` / the upcoming
-- Data Capture domain's `data_captures.process_id` all need ZERO remapping -- a legacy process.id and
-- the new process.id are the same number.
--
-- The only real consequence of not merging: `process.code` has a `UNIQUE(tenant_id, category, code)`
-- constraint that the legacy data does not respect (the same code legitimately repeats). Resolved the
-- same way `user.login_id` collisions were resolved earlier in this migration: for each
-- (company_id, process_id) group, the earliest-created row (lowest id) keeps the code as-is; later
-- rows get `_1`, `_2`, ... appended (confirmed with user -- numeric suffix, not a description-based
-- one). This is a display change from the legacy admin UI (which showed the same code text on every
-- row) purely because the new schema enforces uniqueness where the old one didn't -- it does not
-- change which row is which or lose any configuration.
-- =============================================================================
--
-- Also NOT migrated:
--   - `enable_save_draft` (1144/1147 false, 3 true): no column in new `process` -- superseded by the
--     new `data_capture_draft` design where draft-eligibility is a business rule keyed off process
--     category/code, not a per-process flag.
--
-- Judgment call: `sync_source_process_id` (legacy subsidiary-auto-sync origin, populated on 120/1147
-- rows) is repurposed as `process.copied_from_process_id` (the new schema's "Copy From" traceability
-- column) -- different original mechanisms (auto subsidiary sync vs. manual UI copy) but the same
-- shape of information, and the new column is documented as traceability-only / no business logic
-- depends on it. Since IDs are preserved 1:1 (no merging now), this is a direct reference, no lookup
-- needed.
--
-- Idempotency: NOT idempotent, single run against the state left by the previous two scripts.
--
-- Usage:
--   mysql -u root count_real < backend/src/main/resources/SqlEtcForMigrate/migrate_data_process_from_legacy.sql

-- =============================================================================
-- 0. Temp mapping tables (re-derived; independent of any previous script's session)
-- =============================================================================
CREATE TEMPORARY TABLE _map_tenant (
    old_type      ENUM('COMPANY','GROUP') NOT NULL,
    old_id        INT NOT NULL,
    new_tenant_id INT NOT NULL,
    PRIMARY KEY (old_type, old_id)
);
INSERT INTO _map_tenant (old_type, old_id, new_tenant_id)
SELECT 'COMPANY', c.id, t.id
FROM c168_net_legacy_20260827.company c
JOIN tenant t ON t.tenant_type = 'COMPANY' AND t.code = c.company_id;
INSERT INTO _map_tenant (old_type, old_id, new_tenant_id)
SELECT 'GROUP', g.id, t.id
FROM c168_net_legacy_20260827.groups g
JOIN tenant t ON t.tenant_type = 'GROUP' AND t.code = g.group_code;

CREATE TEMPORARY TABLE _map_currency (
    old_currency_id  INT NOT NULL PRIMARY KEY,
    survivor_id      INT NOT NULL
);
INSERT INTO _map_currency (old_currency_id, survivor_id)
SELECT cu.id, s.id
FROM c168_net_legacy_20260827.currency cu
JOIN (
    SELECT id, company_id, code,
           ROW_NUMBER() OVER (PARTITION BY company_id, code ORDER BY (sync_source = 'subsidiary'), id) AS rn
    FROM c168_net_legacy_20260827.currency
) s ON s.company_id = cu.company_id AND s.code = cu.code
WHERE s.rn = 1;

-- =============================================================================
-- 1. process_description (from `description`; 1:1, ID preserved -- target table is empty and no
--    duplicate (company_id, name) pairs exist in this dataset, verified before running)
-- =============================================================================
INSERT INTO process_description (id, tenant_id, name, created_at)
SELECT d.id, m.new_tenant_id, d.name, CURRENT_TIMESTAMP
FROM c168_net_legacy_20260827.description d
JOIN _map_tenant m ON m.old_type = 'COMPANY' AND m.old_id = d.company_id;

-- =============================================================================
-- 2. process (every legacy row, 1:1, ID preserved -- no merging; category='GAME' -- this legacy
--    table has no BANK processes, those come from `bank_process` in a separate script).
--    code disambiguation: earliest-created row per (company_id, process_id) keeps the code as-is,
--    later ones get _1/_2/... appended (see header note).
-- =============================================================================
INSERT INTO process
    (id, tenant_id, category, code, currency_id, remove_word, replace_word_from, replace_word_to,
     remark, status, created_by, updated_by, created_at, updated_at)
SELECT
    x.id, m.new_tenant_id, 'GAME',
    CASE WHEN x.rn > 1 THEN CONCAT(x.process_id, '_', x.rn - 1) ELSE x.process_id END,
    mc.survivor_id,
    x.remove_word, x.replace_word_from, x.replace_word_to, x.remark,
    CASE WHEN x.status = 'active' THEN 'ACTIVE' ELSE 'INACTIVE' END,
    CASE WHEN x.created_by_type = 'owner' THEN cow.owner_code ELSE cu.login_id END,
    CASE WHEN x.modified_by_type = 'owner' THEN mow.owner_code ELSE mu.login_id END,
    x.dts_created,
    COALESCE(x.dts_modified, x.dts_created)
FROM (
    SELECT p.*, ROW_NUMBER() OVER (PARTITION BY company_id, process_id ORDER BY id) AS rn
    FROM c168_net_legacy_20260827.process p
) x
JOIN _map_tenant m ON m.old_type = 'COMPANY' AND m.old_id = x.company_id
JOIN _map_currency mc ON mc.old_currency_id = x.currency_id
LEFT JOIN owner cow ON cow.id = x.created_by_owner_id AND x.created_by_type = 'owner'
LEFT JOIN user cu ON cu.id = x.created_by AND x.created_by_type = 'user'
LEFT JOIN owner mow ON mow.id = x.modified_by_owner_id AND x.modified_by_type = 'owner'
LEFT JOIN user mu ON mu.id = x.modified_by AND x.modified_by_type = 'user';

-- copied_from_process_id backfill (self-referencing -- all rows must exist first; IDs preserved
-- 1:1 so this is a direct reference, no lookup/remap needed).
UPDATE process pr
JOIN c168_net_legacy_20260827.process p ON p.id = pr.id
SET pr.copied_from_process_id = p.sync_source_process_id
WHERE p.sync_source_process_id IS NOT NULL;

-- =============================================================================
-- 3. process_description_link: 1:1 now (no merging) -- each process links only its own description.
-- =============================================================================
INSERT INTO process_description_link (process_id, description_id)
SELECT p.id, p.description_id
FROM c168_net_legacy_20260827.process p;

-- =============================================================================
-- 4. process_day (day.id 1-7 already matches new day_of_week 1=Mon..7=Sun exactly, verified).
--    process_id copied as-is -- IDs preserved 1:1, no remapping needed.
-- =============================================================================
INSERT INTO process_day (process_id, day_of_week)
SELECT pd.process_id, pd.day_id
FROM c168_net_legacy_20260827.process_day pd;

-- =============================================================================
-- 5. process_submitted (from submitted_processes; scope_type ignored, company_id authoritative,
--    same pattern as every other table in this migration; created_by resolved via user_id+user_type;
--    process_id copied as-is; capture_id left NULL -- Data Capture domain not migrated yet)
-- =============================================================================
INSERT INTO process_submitted (tenant_id, process_id, created_by, capture_date, created_at)
SELECT m.new_tenant_id, sp.process_id,
       CASE WHEN sp.user_type = 'owner' THEN ow.owner_code ELSE u.login_id END,
       sp.capture_date, sp.created_at
FROM c168_net_legacy_20260827.submitted_processes sp
JOIN _map_tenant m ON m.old_type = 'COMPANY' AND m.old_id = sp.company_id
LEFT JOIN owner ow ON ow.id = sp.user_id AND sp.user_type = 'owner'
LEFT JOIN user u ON u.id = sp.user_id AND sp.user_type = 'user';

DROP TEMPORARY TABLE _map_tenant;
DROP TEMPORARY TABLE _map_currency;
