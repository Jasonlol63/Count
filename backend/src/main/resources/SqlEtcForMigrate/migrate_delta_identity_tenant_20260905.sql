-- Incremental DATA sync: identity/tenant domain, legacy c168.net PHP DB -> count_real.
--
-- Third round (2026-09-05). Companion to migrate_delta_identity_tenant_20260903.sql: same approach,
-- one round later. Source = fresh dump `c168_net_legacy_20260905` (today's export). "Already synced"
-- reference = `c168_net_legacy_20260827`, which as of this round actually holds the 2026-09-03 dump's
-- content (that schema is reused/overwritten across rounds rather than renamed -- see MIGRATION_LOG.md
-- discussion from this session). The original `c168_net_legacy_20260827_baseline` (frozen 2026-08-27
-- snapshot) is NOT used here -- for the tables in this script, "new" is judged against count_real
-- itself (id-preserved, no organic growth in count_real for these domains), so which upstream
-- snapshot is "baseline" doesn't matter for correctness, only for the id NOT IN scoping performance.
--
-- id-preservation verified before writing this (2026-09-03 state vs count_real max id): account
-- 5709 == 5709 -> preserved, zero organic growth, safe to reuse legacy id directly.
-- No new owner/company/groups/user rows this round (verified 0 diff vs the 2026-09-03 state) -- this
-- script only actually inserts 1 new `account` row (SU27, id 5710) and its account_tenant_access
-- (if any -- verified 0, this account has no account_company row yet, stays an orphan same as the
-- documented orphan login_ids in MIGRATION_LOG.md §9).
--
-- Explicitly OUT of scope this round, per user decision (2026-09-05): legacy rows that were migrated
-- in an earlier round but have since been reversed/deleted upstream (81 transactions, 88
-- accounting-posted/dismissed rows -- see session notes) are NOT cleaned up here. This round is
-- forward-only sync of genuinely new rows.
--
-- Usage:
--   mysql -u root count_real < backend/src/main/resources/SqlEtcForMigrate/migrate_delta_identity_tenant_20260905.sql

START TRANSACTION;

CREATE TEMPORARY TABLE _map_tenant (
    old_type      ENUM('COMPANY','GROUP') NOT NULL,
    old_id        INT NOT NULL,
    new_tenant_id INT NOT NULL,
    PRIMARY KEY (old_type, old_id)
);

-- =============================================================================
-- 1. owner: new rows only. (0 expected this round, verified.)
-- =============================================================================
INSERT INTO owner (id, owner_code, name, email, password, secondary_password, status, created_by, created_at)
SELECT id, owner_code, name, email, password, secondary_password, UPPER(status), created_by, created_at
FROM c168_net_legacy_20260905.owner
WHERE id NOT IN (SELECT id FROM owner);

-- =============================================================================
-- 2. tenant: new company/group rows only. (0 expected this round, verified.)
-- =============================================================================
INSERT INTO tenant (tenant_type, code, name, owner_id, expiration_date, status, created_by, created_at)
SELECT 'COMPANY', c.company_id, NULL, c.owner_id, c.expiration_date, 'ACTIVE', c.created_by, c.created_at
FROM c168_net_legacy_20260905.company c
WHERE NOT EXISTS (SELECT 1 FROM tenant t WHERE t.tenant_type = 'COMPANY' AND t.code = c.company_id);

INSERT INTO tenant (tenant_type, code, name, owner_id, expiration_date, status, created_by, created_at, updated_at)
SELECT 'GROUP', g.group_code, g.group_name, g.owner_id, g.expiration_date, UPPER(g.status), g.created_by, g.created_at, g.updated_at
FROM c168_net_legacy_20260905.groups g
WHERE NOT EXISTS (SELECT 1 FROM tenant t WHERE t.tenant_type = 'GROUP' AND t.code = g.group_code);

INSERT INTO _map_tenant (old_type, old_id, new_tenant_id)
SELECT 'COMPANY', c.id, t.id
FROM c168_net_legacy_20260905.company c
JOIN tenant t ON t.tenant_type = 'COMPANY' AND t.code = c.company_id;

INSERT INTO _map_tenant (old_type, old_id, new_tenant_id)
SELECT 'GROUP', g.id, t.id
FROM c168_net_legacy_20260905.groups g
JOIN tenant t ON t.tenant_type = 'GROUP' AND t.code = g.group_code;

-- Backfill parent_id for any newly-inserted COMPANY tenant.
UPDATE tenant t
JOIN c168_net_legacy_20260905.company c ON c.company_id = t.code AND t.tenant_type = 'COMPANY'
JOIN tenant pt ON pt.tenant_type = 'GROUP' AND pt.code = c.group_id
SET t.parent_id = pt.id
WHERE c.group_id IS NOT NULL AND t.parent_id IS NULL;

-- =============================================================================
-- 3. account: new rows only. (1 expected this round: SU27 / id 5710.)
-- =============================================================================
INSERT INTO account (id, account_id, name, password, role, status, created_source,
                      payment_alert, alert_day, alert_specific_date, alert_amount, remark, last_login)
SELECT id, account_id, name, password, role, UPPER(status), created_source,
       payment_alert, alert_day, alert_specific_date, alert_amount, remark, last_login
FROM c168_net_legacy_20260905.account
WHERE id NOT IN (SELECT id FROM account);

-- =============================================================================
-- 4. account_tenant_access: new (account, tenant) pairs only. (0 expected -- SU27 has no
--    account_company row yet, verified.)
-- =============================================================================
INSERT INTO account_tenant_access (account_id, tenant_id, created_at, updated_at)
SELECT ac.account_id, m.new_tenant_id, ac.created_at, ac.updated_at
FROM c168_net_legacy_20260905.account_company ac
JOIN _map_tenant m ON m.old_type = 'COMPANY' AND m.old_id = ac.company_id
WHERE NOT EXISTS (
    SELECT 1 FROM account_tenant_access ata
    WHERE ata.account_id = ac.account_id AND ata.tenant_id = m.new_tenant_id
);

-- =============================================================================
-- 5. user: new rows only. (0 expected this round, verified.) Same login_id disambiguation /
--    IT_JK/IT_JS/IT_MS exclusion as the 20260903 script, recomputed over the full current table.
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
    FROM c168_net_legacy_20260905.user u
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
  AND x.id NOT IN (523, 524, 525);

-- =============================================================================
-- 6. user_tenant_access: new (user, tenant) pairs only. (0 expected this round.)
-- =============================================================================
INSERT INTO user_tenant_access (user_id, tenant_id)
SELECT DISTINCT m2.user_id, m.new_tenant_id
FROM c168_net_legacy_20260905.user_company_map m2
JOIN _map_tenant m ON m.old_type = 'COMPANY' AND m.old_id = m2.company_id
WHERE NOT EXISTS (
    SELECT 1 FROM user_tenant_access uta
    WHERE uta.user_id = m2.user_id AND uta.tenant_id = m.new_tenant_id
);

DROP TEMPORARY TABLE _map_tenant;

COMMIT;
