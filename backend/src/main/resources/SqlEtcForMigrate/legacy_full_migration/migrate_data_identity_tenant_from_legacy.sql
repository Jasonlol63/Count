-- One-off DATA migration (not DDL): identity/tenant domain, legacy PHP DB -> Spring Boot tenant model.
--
-- Source: staging DB holding the raw 2026-08-27 c168.net mysqldump, imported as-is
--         (`c168_net_legacy_20260827` below -- adjust if your staging DB name differs).
-- Target: a freshly-built schema.sql database with EMPTY identity tables (owner/tenant/account/
--         user/user_tenant_access/account_tenant_access) -- this script does not attempt to merge
--         with pre-existing rows. Run schema.sql on the target DB first if you haven't.
--
-- Scope: owner, tenant (company+groups merged), account, account_tenant_access, user,
--        user_tenant_access. Sidebar permissions are NOT migrated per-user (see TABLE_MIGRATION.md
--        3.2 / project decision: new model is role-based only via user_role_permission; legacy
--        user.permissions JSON per-user overrides are intentionally dropped). user_tenant_account_access
--        / user_tenant_process_access (from legacy user_company_permissions JSON) are deferred to a
--        follow-up (needs JSON parsing the current MariaDB 10.4 server can't do in pure SQL, and
--        process ACL additionally needs the Process domain migrated first) -- this script defaults
--        every user_tenant_access row to account_acl_mode='ALL', process_acl_mode='ALL' (matches
--        legacy behavior for any user_company_map row that never got a matching
--        user_company_permissions row).
--
-- Known upstream decisions baked into this script (see conversation / TABLE_MIGRATION.md):
--   - company.auto_renew_enabled/auto_renew_period/payment_customer_id/payment_subscription_id/
--     domain_billing_period: legacy fields never actually used (all 0/NULL in a 23-row sample) and
--     have no home in the new schema -- not migrated.
--   - Duplicate login_id collisions: legacy `user.login_id` is NOT unique (verified cases like '9'/
--     'APPLE'/'JS' are several genuinely-independent accounts -- distinct password hashes/emails --
--     that happen to share one login name). Step 5 below disambiguates this automatically and
--     non-destructively (computed inline from the raw source table, staging DB is never mutated):
--     for each login_id, the earliest-created row (lowest id) keeps the name as-is, later rows get
--     `_1`, `_2`, ... appended. Re-running this script against a freshly re-imported staging DB
--     re-derives the same disambiguation from scratch every time -- no manual pre-step needed.
--   - account_company.scope_type ('company' vs 'group') and user_company_map.scope_type are ignored;
--     company_id is authoritative in both tables for every row observed (verified: 0 orphaned
--     scope_type='group' rows without a populated company_id).
--
-- Idempotency: NOT idempotent. Re-running will re-insert duplicate rows (or fail on unique keys
-- once tenant/owner/user rows exist). Intended for a single run against a freshly-built empty DB.
--
-- Usage:
--   mysql -u root count_real < backend/src/main/resources/sql/migrate_data_identity_tenant_from_legacy.sql

-- =============================================================================
-- 0. Temp mapping table (session-scoped; company.id/groups.id -> merged tenant.id)
-- =============================================================================
CREATE TEMPORARY TABLE _map_tenant (
    old_type      ENUM('COMPANY','GROUP') NOT NULL,
    old_id        INT NOT NULL,
    new_tenant_id INT NOT NULL,
    PRIMARY KEY (old_type, old_id)
);

-- =============================================================================
-- 1. owner (1:1, IDs preserved -- target table is empty)
-- =============================================================================
INSERT INTO owner (id, owner_code, name, email, password, secondary_password, status, created_by, created_at)
SELECT id, owner_code, name, email, password, secondary_password, UPPER(status), created_by, created_at
FROM c168_net_legacy_20260827.owner;

-- =============================================================================
-- 2. tenant: company + groups merged (fresh auto-increment IDs; mapped by business code)
-- =============================================================================
INSERT INTO tenant (tenant_type, code, name, owner_id, expiration_date, status, created_by, created_at)
SELECT 'COMPANY', c.company_id, NULL, c.owner_id, c.expiration_date, 'ACTIVE', c.created_by, c.created_at
FROM c168_net_legacy_20260827.company c;

INSERT INTO tenant (tenant_type, code, name, owner_id, expiration_date, status, created_by, created_at, updated_at)
SELECT 'GROUP', g.group_code, g.group_name, g.owner_id, g.expiration_date, UPPER(g.status), g.created_by, g.created_at, g.updated_at
FROM c168_net_legacy_20260827.groups g;

INSERT INTO _map_tenant (old_type, old_id, new_tenant_id)
SELECT 'COMPANY', c.id, t.id
FROM c168_net_legacy_20260827.company c
JOIN tenant t ON t.tenant_type = 'COMPANY' AND t.code = c.company_id;

INSERT INTO _map_tenant (old_type, old_id, new_tenant_id)
SELECT 'GROUP', g.id, t.id
FROM c168_net_legacy_20260827.groups g
JOIN tenant t ON t.tenant_type = 'GROUP' AND t.code = g.group_code;

-- tenant.owner_id above was inserted as the legacy owner.id -- valid as-is since owner IDs were
-- preserved 1:1 in step 1 (no remapping needed for owner).

-- Backfill parent_id: company.group_id (varchar, = groups.group_code) -> parent GROUP tenant.id
UPDATE tenant t
JOIN c168_net_legacy_20260827.company c ON c.company_id = t.code AND t.tenant_type = 'COMPANY'
JOIN tenant pt ON pt.tenant_type = 'GROUP' AND pt.code = c.group_id
SET t.parent_id = pt.id
WHERE c.group_id IS NOT NULL;

-- =============================================================================
-- 3. account (1:1, IDs preserved -- target table is empty)
-- =============================================================================
INSERT INTO account (id, account_id, name, password, role, status, created_source,
                      payment_alert, alert_day, alert_specific_date, alert_amount, remark, last_login)
SELECT id, account_id, name, password, role, UPPER(status), created_source,
       payment_alert, alert_day, alert_specific_date, alert_amount, remark, last_login
FROM c168_net_legacy_20260827.account;

-- =============================================================================
-- 4. account_tenant_access (from account_company; scope_type ignored, company_id authoritative)
-- =============================================================================
INSERT INTO account_tenant_access (account_id, tenant_id, created_at, updated_at)
SELECT ac.account_id, m.new_tenant_id, ac.created_at, ac.updated_at
FROM c168_net_legacy_20260827.account_company ac
JOIN _map_tenant m ON m.old_type = 'COMPANY' AND m.old_id = ac.company_id;

-- =============================================================================
-- 5. user (1:1, IDs preserved -- target table is empty)
--    login_id: disambiguated inline (see header notes) -- earliest-created row per login_id keeps
--    the name, later ones get _1/_2/... appended; source table is read-only, never mutated.
--    role: legacy free-text role -> new user_role.code lookup
--    permission_mode: left at column default (ROLE_DEFAULT) -- legacy per-user permissions JSON
--    is intentionally not migrated (role-based only in the new model, see TABLE_MIGRATION.md 3.2)
-- =============================================================================
INSERT INTO user (id, login_id, name, email, password, secondary_password, role_id, status,
                   read_only, remember_token, remember_token_expires, last_login, created_by, created_at)
SELECT
    x.id,
    CASE WHEN x.rn > 1 THEN CONCAT(x.login_id, '_', x.rn - 1) ELSE x.login_id END,
    x.name, x.email, x.password, x.secondary_password,
    ur.id,
    UPPER(x.status),
    x.read_only, x.remember_token, x.remember_token_expires, x.last_login, x.created_by, x.created_at
FROM (
    SELECT u.*, ROW_NUMBER() OVER (PARTITION BY login_id ORDER BY id) AS rn
    FROM c168_net_legacy_20260827.user u
) x
JOIN user_role ur ON ur.code = CASE LOWER(TRIM(x.role))
    WHEN 'admin'            THEN 'ADMIN'
    WHEN 'manager'          THEN 'MANAGER'
    WHEN 'supervisor'       THEN 'SUPERVISOR'
    WHEN 'accountant'       THEN 'ACCOUNTANT'
    WHEN 'audit'            THEN 'AUDIT'
    WHEN 'customer service' THEN 'CUSTOMER_SERVICE'
    WHEN 'partnership'      THEN 'PARTNERSHIP'
END
-- IT_JK/IT_JS/IT_MS (legacy user.id 523/524/525): confirmed with the user as intentional --
-- these 3 accounts are to be deleted entirely, not backfilled (see
-- migrate_data_user_acl_from_legacy.sql's header, which documents the same decision for the
-- user_company_permissions rows that reference them). This script originally had no exclusion for
-- them and re-inserted all 3 on a fresh re-run (2026-09-03) -- excluded explicitly here so a future
-- re-run reaches the correct end state without a manual cleanup step.
WHERE x.id NOT IN (523, 524, 525);

-- =============================================================================
-- 6. user_tenant_access (from user_company_map; scope_type ignored, company_id authoritative)
--    account_acl_mode/process_acl_mode default to 'ALL' (column defaults) -- CUSTOM-mode rows from
--    legacy user_company_permissions JSON are a follow-up (see header notes).
-- =============================================================================
INSERT IGNORE INTO user_tenant_access (user_id, tenant_id)
SELECT DISTINCT m2.user_id, m.new_tenant_id
FROM c168_net_legacy_20260827.user_company_map m2
JOIN _map_tenant m ON m.old_type = 'COMPANY' AND m.old_id = m2.company_id;

DROP TEMPORARY TABLE _map_tenant;
