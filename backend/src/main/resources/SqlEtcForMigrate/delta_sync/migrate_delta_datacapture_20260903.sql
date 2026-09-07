-- Incremental DATA sync: Data Capture domain -- companion to migrate_delta_process_20260903.sql
-- (run that first, needs `process`/process_duplicate_merge_map). Mirrors
-- migrate_data_datacapture_from_legacy.sql, scoped to rows new since the 2026-08-27 baseline.
--
-- id-preservation verified: data_captures/data_capture_line/data_capture_formula baseline max ids
-- all exactly equal count_real's current max ids (19636/124586/35437) -- zero organic growth, safe
-- to reuse legacy ids directly, same as the original script.
--
-- Verified: none of the 5 new legacy `process` rows use a reserved BANK code (SALARY/BONUS/PROFIT/
-- COMMISSION), so the one-off `UPDATE process SET category='BANK' ...` the original script's prep
-- required does not apply here -- every new process row is a genuine GAME row.
--
-- Usage:
--   mysql -u root count_real < backend/src/main/resources/SqlEtcForMigrate/migrate_delta_datacapture_20260903.sql

START TRANSACTION;

CREATE TEMPORARY TABLE _map_tenant (
    old_type      ENUM('COMPANY','GROUP') NOT NULL,
    old_id        INT NOT NULL,
    new_tenant_id INT NOT NULL,
    PRIMARY KEY (old_type, old_id)
);
INSERT INTO _map_tenant (old_type, old_id, new_tenant_id)
SELECT 'COMPANY', c.id, t.id FROM c168_net_legacy_20260827.company c
JOIN tenant t ON t.tenant_type = 'COMPANY' AND t.code = c.company_id;
INSERT INTO _map_tenant (old_type, old_id, new_tenant_id)
SELECT 'GROUP', g.id, t.id FROM c168_net_legacy_20260827.groups g
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

CREATE TEMPORARY TABLE _resolve_process (
    old_process_id     INT NOT NULL PRIMARY KEY,
    resolved_process_id INT UNSIGNED NOT NULL
);
INSERT INTO _resolve_process (old_process_id, resolved_process_id)
SELECT p.id, COALESCE(m.canonical_process_id, p.id)
FROM c168_net_legacy_20260827.process p
LEFT JOIN process_duplicate_merge_map m ON m.old_process_id = p.id;

-- =============================================================================
-- 1. data_captures: new rows only, id preserved.
-- =============================================================================
INSERT INTO data_captures
    (id, tenant_id, category, capture_date, process_id, currency_id, remark,
     remove_word, replace_word_from, replace_word_to, created_by, created_at)
SELECT
    dc.id, m.new_tenant_id, pr.category, dc.capture_date, rp.resolved_process_id, mc.survivor_id,
    dc.remark, pr.remove_word, pr.replace_word_from, pr.replace_word_to,
    CASE WHEN dc.user_type = 'owner' THEN ow.owner_code ELSE u.login_id END,
    dc.created_at
FROM c168_net_legacy_20260827.data_captures dc
JOIN _map_tenant m ON m.old_type = 'COMPANY' AND m.old_id = dc.company_id
JOIN _resolve_process rp ON rp.old_process_id = dc.process_id
JOIN process pr ON pr.id = rp.resolved_process_id
JOIN _map_currency mc ON mc.old_currency_id = dc.currency_id
LEFT JOIN owner ow ON ow.id = dc.created_by AND dc.user_type = 'owner'
LEFT JOIN user u ON u.id = dc.created_by AND dc.user_type = 'user'
WHERE dc.id NOT IN (SELECT id FROM data_captures);

-- =============================================================================
-- 2. data_capture_line: new rows only, id preserved.
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
JOIN data_captures dc ON dc.id = dcd.capture_id
WHERE dcd.id NOT IN (SELECT id FROM data_capture_line);

-- =============================================================================
-- 3. data_capture_formula: new rows only, id preserved. Same NULL/dangling-process_id skip and
--    INSERT IGNORE dedup (true-duplicate merge collisions) as the original script.
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
WHERE dct.process_id IS NOT NULL AND dct.process_id REGEXP '^[0-9]+$'
  AND dct.id NOT IN (SELECT id FROM data_capture_formula);

DROP TEMPORARY TABLE _map_tenant;
DROP TEMPORARY TABLE _map_currency;
DROP TEMPORARY TABLE _resolve_process;

COMMIT;
