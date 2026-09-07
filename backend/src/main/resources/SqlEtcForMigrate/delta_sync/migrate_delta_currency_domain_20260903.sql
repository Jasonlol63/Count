-- Incremental DATA sync: Currency / Domain / Ownership domain -- companion to
-- migrate_delta_identity_tenant_20260903.sql (run that first). Mirrors
-- migrate_data_currency_domain_ownership_from_legacy.sql, scoped to rows new since the 2026-08-27
-- baseline. See migrate_delta_identity_tenant_20260903.sql's header for the overall approach.
--
-- Verified before writing: none of the 4 new legacy `currency` rows share a (company_id, code) pair
-- with any existing duplicate group (the manual-wins-over-subsidiary dedup only ever matters within
-- a (company_id, code) pair) -- so each is a brand-new pair, survivor_id = its own id, no dedup
-- collision with an already-migrated currency.
--
-- Also covers: tenant_feature_module for the one new company (327 or 331 as appropriate) inserted
-- by the identity/tenant delta -- that domain was never covered by a delta script before.
--
-- Usage:
--   mysql -u root count_real < backend/src/main/resources/SqlEtcForMigrate/migrate_delta_currency_domain_20260903.sql

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

-- =============================================================================
-- 1. currency: recompute survivor dedup over the FULL current legacy table (old + new), same rule
--    as the original script, but only INSERT rows whose survivor id is itself new.
-- =============================================================================
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

INSERT INTO currency (id, tenant_id, code, sync_source, status)
SELECT mc.survivor_id, m.new_tenant_id, cu.code, UPPER(cu.sync_source), 'ACTIVE'
FROM c168_net_legacy_20260827.currency cu
JOIN _map_currency mc ON mc.old_currency_id = cu.id AND mc.survivor_id = cu.id
JOIN _map_tenant m ON m.old_type = 'COMPANY' AND m.old_id = cu.company_id
WHERE mc.survivor_id NOT IN (SELECT id FROM currency);

-- =============================================================================
-- 2. account_currency: new (account, currency) pairs only.
-- =============================================================================
INSERT INTO account_currency (account_id, tenant_id, currency_id, created_at, updated_at)
SELECT ac.account_id, cur.tenant_id, mc.survivor_id, ac.created_at, ac.updated_at
FROM c168_net_legacy_20260827.account_currency ac
JOIN _map_currency mc ON mc.old_currency_id = ac.currency_id
JOIN currency cur ON cur.id = mc.survivor_id
WHERE NOT EXISTS (
    SELECT 1 FROM account_currency x
    WHERE x.account_id = ac.account_id AND x.currency_id = mc.survivor_id
);

-- 2b. Fold account_currency_display_order into sort_order for any newly-inserted account_currency
--     rows (same rule as the original script -- real account_id + flat JSON array only).
UPDATE account_currency ac
JOIN c168_net_legacy_20260827.account_currency_display_order d ON d.account_id = ac.account_id
JOIN currency cu ON cu.id = ac.currency_id
SET ac.sort_order = COALESCE((
    SELECT idx.i + 1
    FROM (SELECT 0 AS i UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3) idx
    WHERE JSON_UNQUOTE(JSON_EXTRACT(d.currency_order, CONCAT('$[', idx.i, ']'))) = cu.code
    LIMIT 1
), ac.sort_order)
WHERE d.account_id > 0
  AND JSON_VALID(d.currency_order)
  AND JSON_TYPE(d.currency_order) = 'ARRAY'
  AND ac.sort_order IS NULL;

-- =============================================================================
-- 3. account_link: NOT id-preserved by the original script (fresh auto-increment id -- verified
--    count_real.account_link's max id, 74, is far below the legacy table's own max id, 244, so the
--    original script never carried legacy ids over here). "New" therefore can't be judged by id --
--    matched on (account_id_1, account_id_2, tenant_id, link_type) instead, scoped to the 2 legacy
--    rows that are new since the baseline snapshot.
-- =============================================================================
INSERT INTO account_link (account_id_1, account_id_2, tenant_id, link_type, source_account_id, created_at, updated_at)
SELECT al.account_id_1, al.account_id_2, m.new_tenant_id, UPPER(al.link_type), al.source_account_id, al.created_at, al.updated_at
FROM c168_net_legacy_20260827.account_link al
JOIN _map_tenant m ON m.old_type = 'COMPANY' AND m.old_id = al.company_id
WHERE al.id NOT IN (SELECT id FROM c168_net_legacy_20260827_baseline.account_link)
  AND NOT EXISTS (
      SELECT 1 FROM account_link x
      WHERE x.account_id_1 = al.account_id_1 AND x.account_id_2 = al.account_id_2
        AND x.tenant_id = m.new_tenant_id AND x.link_type = UPPER(al.link_type)
  );

-- =============================================================================
-- 4. tenant_feature_module: for any newly-inserted tenant only (company or group) -- this domain
--    was never covered by a prior delta script, so also backfill any pre-existing tenant that's
--    missing its rows (defensive, matches original script's WHERE conditions, INSERT naturally
--    no-ops for tenants that already have their module rows since we filter on NOT EXISTS).
-- =============================================================================
INSERT INTO tenant_feature_module (tenant_id, module_id)
SELECT t.id, 1
FROM c168_net_legacy_20260827.company c
JOIN tenant t ON t.tenant_type = 'COMPANY' AND t.code = c.company_id
WHERE c.permissions LIKE '%Games%'
  AND NOT EXISTS (SELECT 1 FROM tenant_feature_module tfm WHERE tfm.tenant_id = t.id AND tfm.module_id = 1);

INSERT INTO tenant_feature_module (tenant_id, module_id)
SELECT t.id, 2
FROM c168_net_legacy_20260827.company c
JOIN tenant t ON t.tenant_type = 'COMPANY' AND t.code = c.company_id
WHERE c.permissions LIKE '%Bank%'
  AND NOT EXISTS (SELECT 1 FROM tenant_feature_module tfm WHERE tfm.tenant_id = t.id AND tfm.module_id = 2);

INSERT INTO tenant_feature_module (tenant_id, module_id)
SELECT t.id, 1
FROM tenant t
WHERE t.tenant_type = 'GROUP'
  AND NOT EXISTS (SELECT 1 FROM tenant_feature_module tfm WHERE tfm.tenant_id = t.id AND tfm.module_id = 1);

DROP TEMPORARY TABLE _map_tenant;
DROP TEMPORARY TABLE _map_currency;

COMMIT;
