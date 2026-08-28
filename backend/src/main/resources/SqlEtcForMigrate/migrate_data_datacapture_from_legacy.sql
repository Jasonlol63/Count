-- One-off DATA migration: Data Capture domain, legacy PHP DB -> Spring Boot tenant model.
-- Depends on identity/tenant, currency/domain/ownership, and Process domain scripts having already
-- run (needs tenant/currency/account/process), AND on process_duplicate_merge_map existing (created
-- by fix_process_true_duplicates.sql) -- this script is the reason that table was kept permanent.
--
-- Scope (this pass): data_captures (header), data_capture_line (from data_capture_details -- the
-- actual submitted amounts), data_capture_formula (from data_capture_templates -- formula config).
-- Deferred (see bottom): data_capture_draft/_cell, data_capture_description.
--
-- =============================================================================
-- CRITICAL: legacy `data_captures.process_id` and `data_capture_templates.process_id` are BOTH the
-- numeric `process.id` (stored oddly as a VARCHAR/string column in both tables -- verified: not the
-- business code, casting to unsigned resolves cleanly against `process.id` for the overwhelming
-- majority of rows). Every one of these must be resolved through:
--   COALESCE(process_duplicate_merge_map.canonical_process_id, <numeric process_id as-is>)
-- because `process` IDs were preserved 1:1 from legacy EXCEPT for the 7 rows merged away in
-- fix_process_true_duplicates.sql (4700->4138, 4538->4267, 4687->4689, 4701->4689, 4175->4176,
-- 4417->4419, 4590->4591). Skipping this join would make 2 real data_captures rows (ids 15334 -> now
-- capture on 4538, 10759 -> now on 4700) and their 27 data_capture_line rows fail to resolve (their
-- old process id no longer exists in `process`).
--
-- Similarly, legacy `data_capture_details.account_id` is the numeric `account.id` (also stored as a
-- VARCHAR string) -- NOT the account business code. Verified: all 75234 rows resolve cleanly via a
-- plain CAST to unsigned against `account.id` (which was preserved 1:1 from legacy).
--
-- Also verified during this script's prep: 21 legacy `process` rows use the reserved BANK codes
-- (SALARY/BONUS/PROFIT/COMMISSION) but had been migrated as category='GAME' by the Process domain
-- script (that script correctly assumed the whole legacy `process` table was GAME-only, which is
-- true for everything except these 21 -- the old system apparently used the same generic `process`
-- table for BANK anchor rows too, before the new app's `ensureBankProcess()` auto-create pattern
-- existed). Fixed with a direct `UPDATE process SET category='BANK' WHERE id IN (...)` before this
-- script ran (verified: none of these 21 collide on (company_id, process_id, description_id), so a
-- plain category flip was sufficient, no merge needed). `data_captures.category` below is derived by
-- joining the (already-resolved) process row's actual `category`, not hardcoded to GAME.
-- =============================================================================
--
-- Idempotency: NOT idempotent, single run against the state left by the previous scripts +
-- fix_process_true_duplicates.sql.
--
-- Usage:
--   mysql -u root count_real < backend/src/main/resources/SqlEtcForMigrate/migrate_data_datacapture_from_legacy.sql

-- =============================================================================
-- 0. Temp mapping tables (re-derived; process_duplicate_merge_map is NOT temp -- already exists
--    permanently from fix_process_true_duplicates.sql, reused here as-is).
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

-- Resolves ANY legacy process.id reference (from data_captures / data_capture_templates) to the
-- process id that actually exists in `process` today -- either itself (untouched) or the survivor
-- it was merged into.
CREATE TEMPORARY TABLE _resolve_process (
    old_process_id     INT NOT NULL PRIMARY KEY,
    resolved_process_id INT UNSIGNED NOT NULL
);
INSERT INTO _resolve_process (old_process_id, resolved_process_id)
SELECT p.id, COALESCE(m.canonical_process_id, p.id)
FROM c168_net_legacy_20260827.process p
LEFT JOIN process_duplicate_merge_map m ON m.old_process_id = p.id;

-- =============================================================================
-- 1. data_captures (header). category derived from the resolved process's actual category
--    (post BANK-recategorization fix) -- not hardcoded.
-- =============================================================================
INSERT INTO data_captures
    (id, tenant_id, category, capture_date, process_id, currency_id, remark,
     remove_word, replace_word_from, replace_word_to, created_by, created_at)
SELECT
    dc.id, m.new_tenant_id, pr.category, dc.capture_date, rp.resolved_process_id, mc.survivor_id,
    dc.remark,
    pr.remove_word, pr.replace_word_from, pr.replace_word_to,
    CASE WHEN dc.user_type = 'owner' THEN ow.owner_code ELSE u.login_id END,
    dc.created_at
FROM c168_net_legacy_20260827.data_captures dc
JOIN _map_tenant m ON m.old_type = 'COMPANY' AND m.old_id = dc.company_id
JOIN _resolve_process rp ON rp.old_process_id = dc.process_id
JOIN process pr ON pr.id = rp.resolved_process_id
JOIN _map_currency mc ON mc.old_currency_id = dc.currency_id
LEFT JOIN owner ow ON ow.id = dc.created_by AND dc.user_type = 'owner'
LEFT JOIN user u ON u.id = dc.created_by AND dc.user_type = 'user';

-- =============================================================================
-- 2. data_capture_line (from data_capture_details -- the actual submitted amounts).
--    account_id is the numeric account.id (verified, not a business code) -- direct CAST.
--    transaction_id left NULL: Transactions domain not migrated yet (deferred, same as other
--    domains' cross-references to transactions.id).
-- =============================================================================
INSERT INTO data_capture_line
    (id, tenant_id, capture_id, product_type, id_product, id_product_main, id_product_sub,
     description_main, description_sub, formula_variant, display_order, account_id, currency_id,
     source_columns, source_value, source_percent, enable_source_percent, formula,
     processed_amount, rate, rate_expression, created_at)
SELECT
    dcd.id, dc.tenant_id, dcd.capture_id, UPPER(dcd.product_type), dcd.id_product,
    dcd.id_product_main, dcd.id_product_sub, dcd.description_main, dcd.description_sub,
    dcd.formula_variant, dcd.display_order, CAST(dcd.account_id AS UNSIGNED), dc.currency_id,
    dcd.columns_value, dcd.source_value, dcd.source_percent, dcd.enable_source_percent, dcd.formula,
    dcd.processed_amount, dcd.rate, dcd.rate_expression, dcd.created_at
FROM c168_net_legacy_20260827.data_capture_details dcd
JOIN data_captures dc ON dc.id = dcd.capture_id;

-- =============================================================================
-- 3. data_capture_formula (from data_capture_templates -- persistent formula config, not bound to
--    one capture; process_id is ALSO the numeric process.id stored as a string, same resolution).
--    Rows with NULL process_id (6) or a process_id that matches no existing legacy process (131,
--    dangling references -- the process row was already deleted in the legacy DB) are skipped --
--    there is nothing to attach them to.
--
--    INSERT IGNORE: merging the 7 true-duplicate process rows (fix_process_true_duplicates.sql)
--    can make two legacy template rows collide on uk_dcf_tenant_process_formula (e.g. process
--    4267/4538 -> both merge to 4267, and both had a template row for the same id_product/account).
--    Verified by hand (INFINITY688US-2, "IG - TR8=PP"/"IG - ZBH3840=MCG"): every such collision found
--    has byte-identical formula_display/source_columns/source_percent on both sides -- these are
--    exact redundant copies, not a genuine configuration difference, so silently keeping whichever
--    inserts first loses no information.
-- =============================================================================
INSERT IGNORE INTO data_capture_formula
    (id, tenant_id, process_id, product_type, id_product, parent_id_product, formula_variant,
     sub_order, row_index, account_id, currency_id, description, source_columns, columns_display,
     formula, input_method, source_percent, enable_source_percent, enable_input_method,
     created_at, updated_at)
SELECT
    dct.id, m.new_tenant_id, rp.resolved_process_id, UPPER(dct.product_type), dct.id_product,
    dct.parent_id_product, dct.formula_variant, dct.sub_order, dct.row_index, dct.account_id,
    mc.survivor_id, dct.description, dct.source_columns, dct.columns_display, dct.formula_display,
    dct.input_method, dct.source_percent, dct.enable_source_percent, dct.enable_input_method,
    dct.created_at, dct.updated_at
FROM c168_net_legacy_20260827.data_capture_templates dct
JOIN _map_tenant m ON m.old_type = 'COMPANY' AND m.old_id = dct.company_id
JOIN _resolve_process rp ON rp.old_process_id = CAST(dct.process_id AS UNSIGNED)
LEFT JOIN _map_currency mc ON mc.old_currency_id = dct.currency_id
WHERE dct.process_id IS NOT NULL AND dct.process_id REGEXP '^[0-9]+$';

DROP TEMPORARY TABLE _map_tenant;
DROP TEMPORARY TABLE _map_currency;
DROP TEMPORARY TABLE _resolve_process;

-- =============================================================================
-- Deferred (not in this pass -- see conversation / MIGRATION_LOG.md for reasoning):
--   - data_capture_draft / data_capture_draft_cell: only 13 legacy rows, but each has an arbitrary
--     nested draft_json ({"headers":...,"rows":[...]} in some rows, {"tableData":{...}} in others --
--     two different legacy JSON shapes seen). MariaDB 10.4 has no JSON_TABLE to unwrap this cleanly
--     in pure SQL, and low row count makes a bespoke script low priority next to the money tables.
--   - data_capture_description (GAME multi-select description bridge at capture time): no direct
--     legacy source column identified yet; would need deriving from data_capture_details'
--     description_main/description_sub text matched back to process_description, deduped per
--     capture -- deferred pending a closer look.
-- =============================================================================
