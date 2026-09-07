-- Incremental DATA sync: identity/tenant domain, legacy c168.net PHP DB -> count_real.
--
-- Companion to migrate_data_identity_tenant_from_legacy.sql. That script was a one-off full
-- migration from the 2026-08-27 legacy snapshot into an empty target. This script picks up only
-- what changed in the legacy system between the 2026-08-27 snapshot (restored separately as
-- `c168_net_legacy_20260827_baseline` for diffing) and the current 2026-09-03 snapshot
-- (`c168_net_legacy_20260827`), and inserts ONLY those new rows into count_real -- which by now
-- also has ~5 days of its own organic growth (new transactions, accounting postings, etc.) that
-- must not be touched or collided with.
--
-- Verified before writing this: for every table below, count_real's own auto-increment high-water
-- mark exactly equals `c168_net_legacy_20260827_baseline`'s (owner/company/groups/account/user all
-- had ZERO organic growth in count_real since migration -- these domains are effectively
-- admin-panel-only, not part of daily live usage). So every new-in-current-snapshot legacy id is
-- guaranteed to be a genuinely free id in count_real -- 1:1 id preservation is safe here, same as
-- the original script.
--
-- Idempotent-safe to re-run: every INSERT below is scoped to `id NOT IN (SELECT id FROM target)`
-- or an equivalent NOT EXISTS pair-check, so running this twice against the same two snapshots is a
-- no-op the second time.
--
-- Usage:
--   mysql -u root count_real < backend/src/main/resources/SqlEtcForMigrate/migrate_delta_identity_tenant_20260903.sql

START TRANSACTION;

-- =============================================================================
-- 0. Temp mapping table, rebuilt over the FULL current legacy tenant set (old + new) so lookups for
--    new rows resolve correctly -- mirrors the original script's approach.
-- =============================================================================
CREATE TEMPORARY TABLE _map_tenant (
    old_type      ENUM('COMPANY','GROUP') NOT NULL,
    old_id        INT NOT NULL,
    new_tenant_id INT NOT NULL,
    PRIMARY KEY (old_type, old_id)
);

-- =============================================================================
-- 1. owner: new rows only.
-- =============================================================================
INSERT INTO owner (id, owner_code, name, email, password, secondary_password, status, created_by, created_at)
SELECT id, owner_code, name, email, password, secondary_password, UPPER(status), created_by, created_at
FROM c168_net_legacy_20260827.owner
WHERE id NOT IN (SELECT id FROM owner);

-- =============================================================================
-- 2. tenant: new company/group rows only (fresh auto-increment ids, mapped by business code).
-- =============================================================================
INSERT INTO tenant (tenant_type, code, name, owner_id, expiration_date, status, created_by, created_at)
SELECT 'COMPANY', c.company_id, NULL, c.owner_id, c.expiration_date, 'ACTIVE', c.created_by, c.created_at
FROM c168_net_legacy_20260827.company c
WHERE NOT EXISTS (SELECT 1 FROM tenant t WHERE t.tenant_type = 'COMPANY' AND t.code = c.company_id);

INSERT INTO tenant (tenant_type, code, name, owner_id, expiration_date, status, created_by, created_at, updated_at)
SELECT 'GROUP', g.group_code, g.group_name, g.owner_id, g.expiration_date, UPPER(g.status), g.created_by, g.created_at, g.updated_at
FROM c168_net_legacy_20260827.groups g
WHERE NOT EXISTS (SELECT 1 FROM tenant t WHERE t.tenant_type = 'GROUP' AND t.code = g.group_code);

INSERT INTO _map_tenant (old_type, old_id, new_tenant_id)
SELECT 'COMPANY', c.id, t.id
FROM c168_net_legacy_20260827.company c
JOIN tenant t ON t.tenant_type = 'COMPANY' AND t.code = c.company_id;

INSERT INTO _map_tenant (old_type, old_id, new_tenant_id)
SELECT 'GROUP', g.id, t.id
FROM c168_net_legacy_20260827.groups g
JOIN tenant t ON t.tenant_type = 'GROUP' AND t.code = g.group_code;

-- Backfill parent_id for any newly-inserted COMPANY tenant.
UPDATE tenant t
JOIN c168_net_legacy_20260827.company c ON c.company_id = t.code AND t.tenant_type = 'COMPANY'
JOIN tenant pt ON pt.tenant_type = 'GROUP' AND pt.code = c.group_id
SET t.parent_id = pt.id
WHERE c.group_id IS NOT NULL AND t.parent_id IS NULL;

-- =============================================================================
-- 3. account: new rows only.
-- =============================================================================
INSERT INTO account (id, account_id, name, password, role, status, created_source,
                      payment_alert, alert_day, alert_specific_date, alert_amount, remark, last_login)
SELECT id, account_id, name, password, role, UPPER(status), created_source,
       payment_alert, alert_day, alert_specific_date, alert_amount, remark, last_login
FROM c168_net_legacy_20260827.account
WHERE id NOT IN (SELECT id FROM account);

-- =============================================================================
-- 4. account_tenant_access: new (account, tenant) pairs only.
-- =============================================================================
INSERT INTO account_tenant_access (account_id, tenant_id, created_at, updated_at)
SELECT ac.account_id, m.new_tenant_id, ac.created_at, ac.updated_at
FROM c168_net_legacy_20260827.account_company ac
JOIN _map_tenant m ON m.old_type = 'COMPANY' AND m.old_id = ac.company_id
WHERE NOT EXISTS (
    SELECT 1 FROM account_tenant_access ata
    WHERE ata.account_id = ac.account_id AND ata.tenant_id = m.new_tenant_id
);

-- =============================================================================
-- 5. user: new rows only. login_id disambiguation recomputed over the FULL current legacy `user`
--    table (old + new) so a new row sharing a login_id with an already-migrated row gets the same
--    suffix a full re-run would have assigned it; final INSERT still only touches new ids.
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
WHERE x.id NOT IN (SELECT id FROM user)
  -- IT_JK/IT_JS/IT_MS (523/524/525): deliberately excluded by the original migration per a
  -- confirmed decision in migrate_data_user_acl_from_legacy.sql's header ("intentional -- those 3
  -- accounts are to be deleted entirely, not backfilled"). The plain id-not-in-target filter above
  -- can't tell "genuinely new legacy row" apart from "row the original migration intentionally
  -- skipped" -- caught by post-run row count verification when this delta script first ran
  -- (2026-09-03) and corrected by hand; excluded explicitly here so a future re-run stays correct.
  AND x.id NOT IN (523, 524, 525);

-- =============================================================================
-- 6. user_tenant_access: new (user, tenant) pairs only.
-- =============================================================================
INSERT INTO user_tenant_access (user_id, tenant_id)
SELECT DISTINCT m2.user_id, m.new_tenant_id
FROM c168_net_legacy_20260827.user_company_map m2
JOIN _map_tenant m ON m.old_type = 'COMPANY' AND m.old_id = m2.company_id
WHERE NOT EXISTS (
    SELECT 1 FROM user_tenant_access uta
    WHERE uta.user_id = m2.user_id AND uta.tenant_id = m.new_tenant_id
);

DROP TEMPORARY TABLE _map_tenant;

COMMIT;
