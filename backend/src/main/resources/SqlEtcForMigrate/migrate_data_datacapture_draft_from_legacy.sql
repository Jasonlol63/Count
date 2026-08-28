-- One-off DATA migration: Data Capture draft tables (data_capture_draft / data_capture_draft_cell).
-- Only 13 legacy rows total -- kept separate from migrate_data_datacapture_from_legacy.sql because
-- the source `draft_json` needs bespoke JSON unwrapping (MariaDB 10.4 has no JSON_TABLE).
--
-- Legacy `draft_json` shape (verified across all 13 rows): either a flat object
-- {"headers":[...],"rows":[[...]],"rowCount":26,"colCount":N} or the same thing one level deeper
-- under a "tableData" key ({"tableData":{"headers":...}}) -- both seen, handled via COALESCE of the
-- two JSON paths. Each entry in "rows" is itself an array: position 0 is a {"type":"header",
-- "value":"A"} row-label cell (redundant with row_index, not stored), positions 1..colCount-1 are
-- {"type":"data","value":"...","col":N} cells. All 13 rows have rowCount=26; colCount maxes out at
-- 22 -- a fixed UNION ALL of row 0..25 / col 0..20 covers every row with margin, verified against
-- the actual max before writing this.
--
-- col_index is 1-based in data_capture_draft_cell (matches DataCaptureServiceImpl.normalizeCells /
-- extractCellsFromTableData, both reject colIndex<1, and the UI column headers start at "1") -- an
-- earlier version of this script stored 0-based col_index (cidx.i as-is), which silently dropped the
-- first data column (col_index=0) on read in the running app -- found post-migration when a
-- CX/SALARY draft was missing its row-label names (KAIYUAN/SHIHUI), see MIGRATION_LOG.md §11. Fixed
-- below by storing cidx.i + 1.
--
-- `process_key` resolution (verified per-row, only 13 total): numeric strings ("4220","4221", ...)
-- are the same "process.id stored as text" pattern as data_capture_templates.process_id -- resolved
-- via process_duplicate_merge_map like everywhere else. Text values ("salary","commission") are the
-- reserved BANK codes -- resolved by matching `process.code` (case-insensitive) within the row's own
-- tenant AND category='BANK'.
--
-- NOT invented: a few of these text-code rows are scoped to a GROUP tenant (scope_type='group'), and
-- the Process domain migration never created any BANK-category process for a GROUP tenant (the
-- legacy `process` table is company-scoped only -- GROUP-tenant BANK anchors, if they exist at all,
-- are created on-demand at runtime by the new app's ensureBankProcess(), which hasn't run for these
-- yet). Rather than fabricate a process row here, any draft row whose process can't be resolved
-- against what's ALREADY in `process` is skipped (verified count reported, not silently dropped).
--
-- Idempotent-ish: re-running after a partial failure requires clearing data_capture_draft/_cell
-- first (small enough to just DELETE and re-run in full).
--
-- Usage:
--   mysql -u root count_real < backend/src/main/resources/SqlEtcForMigrate/migrate_data_datacapture_draft_from_legacy.sql

CREATE TEMPORARY TABLE _map_tenant (
    old_type      ENUM('COMPANY','GROUP') NOT NULL,
    old_id        VARCHAR(50) NOT NULL,
    new_tenant_id INT NOT NULL,
    PRIMARY KEY (old_type, old_id)
);
INSERT INTO _map_tenant (old_type, old_id, new_tenant_id)
SELECT 'COMPANY', c.id, t.id
FROM c168_net_legacy_20260827.company c
JOIN tenant t ON t.tenant_type = 'COMPANY' AND t.code = c.company_id;
INSERT INTO _map_tenant (old_type, old_id, new_tenant_id)
SELECT 'GROUP', g.group_code, t.id
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

-- Resolve each of the 13 legacy draft rows to (tenant_id, process_id) or NULL if unresolvable.
CREATE TEMPORARY TABLE _resolve_draft (
    old_draft_id  INT NOT NULL PRIMARY KEY,
    tenant_id     INT UNSIGNED,
    process_id    INT UNSIGNED,
    currency_id   INT UNSIGNED
);
INSERT INTO _resolve_draft (old_draft_id, tenant_id, process_id, currency_id)
SELECT
    d.id,
    mt.new_tenant_id,
    COALESCE(
        -- numeric process_key -> same "id stored as text" pattern as data_capture_templates
        (SELECT COALESCE(pm.canonical_process_id, CAST(d.process_key AS UNSIGNED))
         FROM c168_net_legacy_20260827.process lp
         LEFT JOIN process_duplicate_merge_map pm ON pm.old_process_id = lp.id
         WHERE d.process_key REGEXP '^[0-9]+$' AND lp.id = CAST(d.process_key AS UNSIGNED)),
        -- text process_key -> reserved BANK code, resolved within this row's own tenant
        (SELECT p.id FROM process p
         WHERE NOT (d.process_key REGEXP '^[0-9]+$')
           AND p.tenant_id = mt.new_tenant_id
           AND p.category = 'BANK'
           AND UPPER(p.code) = UPPER(d.process_key)
         LIMIT 1)
    ),
    mc.survivor_id
FROM c168_net_legacy_20260827.data_capture_draft d
JOIN _map_tenant mt ON mt.old_type = UPPER(d.scope_type)
    AND mt.old_id = (CASE WHEN d.scope_type = 'company' THEN d.company_id ELSE d.group_id END)
LEFT JOIN _map_currency mc ON mc.old_currency_id = d.currency_id;

-- =============================================================================
-- 1. data_capture_draft header (only rows that resolved to a real process; id preserved 1:1)
-- =============================================================================
INSERT INTO data_capture_draft (id, tenant_id, process_id, currency_id, updated_at, created_at)
SELECT d.id, r.tenant_id, r.process_id, r.currency_id, d.updated_at, d.updated_at
FROM c168_net_legacy_20260827.data_capture_draft d
JOIN _resolve_draft r ON r.old_draft_id = d.id AND r.process_id IS NOT NULL;

-- =============================================================================
-- 2. data_capture_draft_cell: fixed-range unwrap (row 0..25, col 0..20 -- covers the observed max of
--    rowCount=26/colCount=22 with margin). Only non-empty "data"-typed cells are stored (matches the
--    documented "空格不落库" rule); the row-label header cell at array position 0 is skipped.
-- =============================================================================
INSERT INTO data_capture_draft_cell (draft_id, row_index, col_index, cell_value, updated_at)
SELECT
    d.id,
    ridx.i,
    cidx.i + 1,
    JSON_UNQUOTE(JSON_EXTRACT(
        COALESCE(JSON_EXTRACT(d.draft_json, '$.rows'), JSON_EXTRACT(d.draft_json, '$.tableData.rows')),
        CONCAT('$[', ridx.i, '][', cidx.i + 1, '].value')
    )),
    d.updated_at
FROM c168_net_legacy_20260827.data_capture_draft d
JOIN _resolve_draft r ON r.old_draft_id = d.id AND r.process_id IS NOT NULL
CROSS JOIN (
    SELECT 0 AS i UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
    UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12 UNION ALL SELECT 13 UNION ALL SELECT 14
    UNION ALL SELECT 15 UNION ALL SELECT 16 UNION ALL SELECT 17 UNION ALL SELECT 18 UNION ALL SELECT 19
    UNION ALL SELECT 20 UNION ALL SELECT 21 UNION ALL SELECT 22 UNION ALL SELECT 23 UNION ALL SELECT 24
    UNION ALL SELECT 25
) ridx
CROSS JOIN (
    SELECT 0 AS i UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
    UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12 UNION ALL SELECT 13 UNION ALL SELECT 14
    UNION ALL SELECT 15 UNION ALL SELECT 16 UNION ALL SELECT 17 UNION ALL SELECT 18 UNION ALL SELECT 19
    UNION ALL SELECT 20
) cidx
WHERE JSON_UNQUOTE(JSON_EXTRACT(
        COALESCE(JSON_EXTRACT(d.draft_json, '$.rows'), JSON_EXTRACT(d.draft_json, '$.tableData.rows')),
        CONCAT('$[', ridx.i, '][', cidx.i + 1, '].type')
      )) = 'data'
  AND JSON_UNQUOTE(JSON_EXTRACT(
        COALESCE(JSON_EXTRACT(d.draft_json, '$.rows'), JSON_EXTRACT(d.draft_json, '$.tableData.rows')),
        CONCAT('$[', ridx.i, '][', cidx.i + 1, '].value')
      )) IS NOT NULL
  AND JSON_UNQUOTE(JSON_EXTRACT(
        COALESCE(JSON_EXTRACT(d.draft_json, '$.rows'), JSON_EXTRACT(d.draft_json, '$.tableData.rows')),
        CONCAT('$[', ridx.i, '][', cidx.i + 1, '].value')
      )) != '';

DROP TEMPORARY TABLE _map_tenant;
DROP TEMPORARY TABLE _map_currency;
DROP TEMPORARY TABLE _resolve_draft;
