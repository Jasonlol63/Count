-- Incremental DATA sync: Process domain (GAME category) -- companion to
-- migrate_delta_identity_tenant_20260903.sql / migrate_delta_currency_domain_20260903.sql (run
-- those first). Mirrors migrate_data_process_from_legacy.sql, scoped to rows new since the
-- 2026-08-27 baseline.
--
-- id-preservation verified per table before writing this (baseline vs count_real max id):
--   process_description (from `description`): 2134 == 2134 -> preserved, safe to reuse legacy id.
--   process:                                   4704 == 4704 -> preserved, safe to reuse legacy id.
--   process_submitted (from `submitted_processes`): baseline 12795, count_real 15796 -> NOT
--     preserved (fresh auto-increment, matches the original script's column list which never
--     included id) -- deduped here via a composite NOT EXISTS guard instead.
--
-- Verified: none of the 5 new legacy `process` rows are "true duplicates" (same company_id +
-- process_id + description_id) of anything already migrated -- process_duplicate_merge_map (the
-- one-off 7-row merge from fix_process_true_duplicates.sql) does not apply to any of these 5.
-- 2 of the 5 (ids 4707, 4709) DO share their (company_id, process_id) business code with an
-- already-migrated row under a different description -- the code-disambiguation suffix logic below
-- (recomputed over the FULL current legacy table, same as the original script) assigns them the
-- correct next suffix automatically.
--
-- Usage:
--   mysql -u root count_real < backend/src/main/resources/SqlEtcForMigrate/migrate_delta_process_20260903.sql

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

-- =============================================================================
-- 1. process_description: new rows only, id preserved.
-- =============================================================================
INSERT INTO process_description (id, tenant_id, name, created_at)
SELECT d.id, m.new_tenant_id, d.name, CURRENT_TIMESTAMP
FROM c168_net_legacy_20260827.description d
JOIN _map_tenant m ON m.old_type = 'COMPANY' AND m.old_id = d.company_id
WHERE d.id NOT IN (SELECT id FROM process_description);

-- =============================================================================
-- 2. process: new rows only, id preserved. Code disambiguation recomputed over the full current
--    legacy table so a new row sharing (company_id, process_id) with an already-migrated row gets
--    the correct next suffix.
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
LEFT JOIN user mu ON mu.id = x.modified_by AND x.modified_by_type = 'user'
WHERE x.id NOT IN (SELECT id FROM process)
  -- Exclude the 7 legacy process ids fix_process_true_duplicates.sql already merged away
  -- (process_duplicate_merge_map.old_process_id) -- caught by post-run row count verification
  -- when this delta script first ran (2026-09-03): it had re-inserted all 7 as if they were new,
  -- since they're absent from `process` (by design, having been merged) but still exist in the
  -- legacy source. Excluded explicitly here so a future re-run stays correct.
  AND x.id NOT IN (SELECT old_process_id FROM process_duplicate_merge_map);

-- copied_from_process_id backfill for the new rows only.
UPDATE process pr
JOIN c168_net_legacy_20260827.process p ON p.id = pr.id
SET pr.copied_from_process_id = p.sync_source_process_id
WHERE p.sync_source_process_id IS NOT NULL
  AND p.id NOT IN (SELECT id FROM c168_net_legacy_20260827_baseline.process);

-- =============================================================================
-- 3. process_description_link: 1:1 with the new process rows.
-- =============================================================================
INSERT INTO process_description_link (process_id, description_id)
SELECT p.id, p.description_id
FROM c168_net_legacy_20260827.process p
WHERE p.id NOT IN (SELECT id FROM c168_net_legacy_20260827_baseline.process)
  AND EXISTS (SELECT 1 FROM process pr2 WHERE pr2.id = p.id)  -- excludes merged-away ids, see §2
  AND NOT EXISTS (SELECT 1 FROM process_description_link l WHERE l.process_id = p.id);

-- =============================================================================
-- 4. process_day: new (process_id, day_of_week) pairs only.
-- =============================================================================
INSERT INTO process_day (process_id, day_of_week)
SELECT pd.process_id, pd.day_id
FROM c168_net_legacy_20260827.process_day pd
WHERE EXISTS (SELECT 1 FROM process pr2 WHERE pr2.id = pd.process_id)  -- excludes merged-away ids, see §2
  AND NOT EXISTS (
    SELECT 1 FROM process_day x WHERE x.process_id = pd.process_id AND x.day_of_week = pd.day_id
);

-- =============================================================================
-- 5. process_submitted: NOT id-preserved by the original script (fresh auto-increment) -- scoped to
--    legacy rows new since the baseline, deduped via a composite NOT EXISTS guard against
--    (tenant_id, process_id, created_by, capture_date) to stay safe to re-run.
-- =============================================================================
-- process_id redirected through process_duplicate_merge_map (COALESCE canonical id) -- 2 of the
-- 528 new legacy rows reference process ids merged away by fix_process_true_duplicates.sql (4700,
-- 4701), same treatment that script gave the original 7 merged ids' own submission history.
INSERT INTO process_submitted (tenant_id, process_id, created_by, capture_date, created_at)
SELECT m.new_tenant_id, COALESCE(pdm.canonical_process_id, sp.process_id),
       CASE WHEN sp.user_type = 'owner' THEN ow.owner_code ELSE u.login_id END,
       sp.capture_date, sp.created_at
FROM c168_net_legacy_20260827.submitted_processes sp
JOIN _map_tenant m ON m.old_type = 'COMPANY' AND m.old_id = sp.company_id
LEFT JOIN owner ow ON ow.id = sp.user_id AND sp.user_type = 'owner'
LEFT JOIN user u ON u.id = sp.user_id AND sp.user_type = 'user'
LEFT JOIN process_duplicate_merge_map pdm ON pdm.old_process_id = sp.process_id
WHERE sp.id NOT IN (SELECT id FROM c168_net_legacy_20260827_baseline.submitted_processes)
  AND NOT EXISTS (
      SELECT 1 FROM process_submitted x
      WHERE x.tenant_id = m.new_tenant_id AND x.process_id = COALESCE(pdm.canonical_process_id, sp.process_id)
        AND x.created_by = CASE WHEN sp.user_type = 'owner' THEN ow.owner_code ELSE u.login_id END
        AND x.capture_date = sp.capture_date
  );

DROP TEMPORARY TABLE _map_tenant;
DROP TEMPORARY TABLE _map_currency;

COMMIT;
