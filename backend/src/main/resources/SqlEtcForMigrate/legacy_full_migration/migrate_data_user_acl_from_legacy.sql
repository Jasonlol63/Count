-- One-off DATA migration: fine-grained account/process ACL, legacy PHP DB -> Spring Boot tenant model.
--
-- Source: staging DB holding the raw 2026-08-27 c168.net mysqldump (`c168_net_legacy_20260827`).
-- Target: count_real -- requires identity/tenant (migrate_data_identity_tenant_from_legacy.sql) and
--         Process domain (migrate_data_process_from_legacy.sql, for process_duplicate_merge_map)
--         already migrated. Was deferred at the time of the original identity migration (MariaDB
--         10.4 has no JSON_TABLE, and process ACL needed process.id to exist first) -- both blockers
--         are gone now, so this fills in MIGRATION_LOG.md §2's documented gap.
--
-- Scope: legacy `user_company_permissions.account_permissions` / `process_permissions` (JSON arrays)
-- -> `user_tenant_access.account_acl_mode`/`process_acl_mode` (CUSTOM/NONE, was defaulted to ALL for
-- every row) + `user_tenant_account_access` / `user_tenant_process_access` (the actual allow-lists).
--
-- JSON shape (verified across all 42 rows): array of objects, e.g.
-- account_permissions: [{"id":4594,"account_id":"AG"}, ...] -- "id" is the legacy account.id (INT,
--   preserved 1:1 by the identity migration -- no business-code lookup needed, use "id" directly).
-- process_permissions: [{"id":4250,"process_id":"SALARY","description":"SALARY"}, ...] -- "id" is the
--   legacy process.id; "process_id" here is confusingly the business CODE text, not a numeric id --
--   ignored. process.id needs the SAME process_duplicate_merge_map resolution used everywhere else in
--   this migration (7 ids merged during the Process domain migration, see MIGRATION_LOG.md §4.2).
-- No JSON_TABLE available (MariaDB 10.4) -- unwrapped via a fixed 0..499 numbers cross join (JSON
-- arrays here max out at 416 elements, verified), same technique used for the draft-cell migration.
--
-- account_acl_mode / process_acl_mode: CUSTOM when the JSON array is non-empty, NONE when it's an
-- empty array (`[]`) -- verified this is a real, deliberate "zero access" setting, not a placeholder:
-- the row exists at all only because someone explicitly saved a CUSTOM permission entry for that
-- user+company. Functionally NONE and "CUSTOM with 0 rows in the access table" behave identically in
-- the app (UserServiceImpl short-circuits NONE to an empty list; CUSTOM falls through to the join,
-- which is also empty) -- NONE is used because it states the intent explicitly rather than relying on
-- an empty join, and skips a redundant query.
--
-- Orphans found and excluded (documented, not fabricated):
--   - 5 of 42 user_company_permissions rows don't resolve to any existing user_tenant_access row:
--     - 3 (user_id 523/524/525, "IT_JK"/"IT_JS"/"IT_MS") -- these users were never migrated into
--       `user`. Confirmed with the user this is intentional (those 3 accounts are to be deleted
--       entirely, not backfilled) -- left alone, not created.
--     - 2 (user_id 280/company 123="95", user_id 299/company 137="CX") -- these users exist and
--       resolve to a real tenant, but have no matching user_tenant_access row at all (their
--       legacy user_company_map entry for that company doesn't exist either) -- same "historical
--       orphan" pattern seen throughout this migration (a permission entry surviving after its
--       access grant was removed), not fabricated a tenant access row to attach it to.
--   - Within the 37 resolvable rows: 62 of 5696 process_permissions entries reference a process.id
--     that doesn't exist even after the merge-map lookup (deleted process, same "orphan" pattern as
--     everywhere else), and 172 of 5687 account_permissions entries reference a similarly-deleted
--     account.id. Both skipped via EXISTS guards -- silently dropping a handful of stale entries
--     inside an otherwise-valid permission list is the right call here (matches how every other
--     domain in this migration treats individual dangling references), as opposed to the 5 whole-row
--     orphans above where the entire row has nowhere to attach.
--
-- Idempotency: NOT idempotent (plain INSERT, no dedup dance; the UPDATE is naturally idempotent since
-- it always recomputes from the same source) -- intended for a single run against
-- user_tenant_account_access/user_tenant_process_access still empty.
--
-- Usage:
--   mysql -u root count_real < backend/src/main/resources/SqlEtcForMigrate/migrate_data_user_acl_from_legacy.sql

-- =============================================================================
-- 0. Session-scoped helpers: tenant resolution (company/group -> tenant.id, same as every other
--    script) and a 0..499 numbers table for JSON array unwrapping (covers the observed max of 416
--    with margin).
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

CREATE TEMPORARY TABLE _numbers (i INT PRIMARY KEY);
INSERT INTO _numbers (i)
SELECT (h.n + t.n + o.n)
FROM (SELECT 0 n UNION ALL SELECT 100 UNION ALL SELECT 200 UNION ALL SELECT 300 UNION ALL SELECT 400) h,
     (SELECT 0 n UNION ALL SELECT 10 UNION ALL SELECT 20 UNION ALL SELECT 30 UNION ALL SELECT 40 UNION ALL SELECT 50 UNION ALL SELECT 60 UNION ALL SELECT 70 UNION ALL SELECT 80 UNION ALL SELECT 90) t,
     (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) o;

-- Resolve each legacy permission row to a real user_tenant_access.id, or NULL if unresolvable.
CREATE TEMPORARY TABLE _resolve_acl (
    ucp_id       INT NOT NULL PRIMARY KEY,
    uta_id       BIGINT UNSIGNED
);
INSERT INTO _resolve_acl (ucp_id, uta_id)
SELECT ucp.id, uta.id
FROM c168_net_legacy_20260827.user_company_permissions ucp
LEFT JOIN _map_tenant m ON m.old_type = 'COMPANY' AND m.old_id = ucp.company_id
LEFT JOIN user_tenant_access uta ON uta.user_id = ucp.user_id AND uta.tenant_id = m.new_tenant_id;

-- =============================================================================
-- 1. account_acl_mode / process_acl_mode: CUSTOM (non-empty array) or NONE (empty array []). Only
--    touches rows with a resolvable user_tenant_access; unresolvable rows change nothing (matches
--    "orphan, leave as-is" for the other 5).
-- =============================================================================
UPDATE user_tenant_access uta
JOIN _resolve_acl r ON r.uta_id = uta.id
JOIN c168_net_legacy_20260827.user_company_permissions ucp ON ucp.id = r.ucp_id
SET uta.account_acl_mode = IF(JSON_LENGTH(ucp.account_permissions) > 0, 'CUSTOM', 'NONE'),
    uta.process_acl_mode = IF(JSON_LENGTH(ucp.process_permissions) > 0, 'CUSTOM', 'NONE');

-- =============================================================================
-- 2. user_tenant_account_access: one row per resolvable account_permissions[i].id.
-- =============================================================================
INSERT INTO user_tenant_account_access (user_tenant_access_id, account_id)
SELECT DISTINCT r.uta_id, CAST(JSON_UNQUOTE(JSON_EXTRACT(ucp.account_permissions, CONCAT('$[', n.i, '].id'))) AS UNSIGNED)
FROM c168_net_legacy_20260827.user_company_permissions ucp
JOIN _resolve_acl r ON r.ucp_id = ucp.id AND r.uta_id IS NOT NULL
JOIN _numbers n ON n.i < JSON_LENGTH(ucp.account_permissions)
JOIN account a ON a.id = CAST(JSON_UNQUOTE(JSON_EXTRACT(ucp.account_permissions, CONCAT('$[', n.i, '].id'))) AS UNSIGNED);

-- =============================================================================
-- 3. user_tenant_process_access: one row per resolvable process_permissions[i].id, resolved through
--    process_duplicate_merge_map like every other domain that references legacy process.id.
-- =============================================================================
INSERT INTO user_tenant_process_access (user_tenant_access_id, process_id)
SELECT DISTINCT r.uta_id,
    COALESCE(
        pm.canonical_process_id,
        CAST(JSON_UNQUOTE(JSON_EXTRACT(ucp.process_permissions, CONCAT('$[', n.i, '].id'))) AS UNSIGNED)
    )
FROM c168_net_legacy_20260827.user_company_permissions ucp
JOIN _resolve_acl r ON r.ucp_id = ucp.id AND r.uta_id IS NOT NULL
JOIN _numbers n ON n.i < JSON_LENGTH(ucp.process_permissions)
LEFT JOIN process_duplicate_merge_map pm
    ON pm.old_process_id = CAST(JSON_UNQUOTE(JSON_EXTRACT(ucp.process_permissions, CONCAT('$[', n.i, '].id'))) AS UNSIGNED)
JOIN process p
    ON p.id = COALESCE(
        pm.canonical_process_id,
        CAST(JSON_UNQUOTE(JSON_EXTRACT(ucp.process_permissions, CONCAT('$[', n.i, '].id'))) AS UNSIGNED)
    );

DROP TEMPORARY TABLE _map_tenant;
DROP TEMPORARY TABLE _numbers;
DROP TEMPORARY TABLE _resolve_acl;
