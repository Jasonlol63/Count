-- Incremental DATA sync: Currency / Domain / Ownership domain -- third round (2026-09-05).
-- Companion to migrate_delta_identity_tenant_20260905.sql (run that first). Mirrors
-- migrate_delta_currency_domain_20260903.sql one round later. Source = `c168_net_legacy_20260905`.
-- "Already synced" reference for the non-id-preserved table below (account_link) =
-- `c168_net_legacy_20260827` (this round's prior-state snapshot, actually holding the 2026-09-03
-- dump's content -- see identity_tenant script's header for why).
--
-- Verified before writing: no new `currency` rows this round (0 diff vs 2026-09-03 state) -- the
-- currency INSERT below is a no-op this round, kept for structural parity with the original script.
-- 4 new `account_currency` rows (account 5710 the new SU27 account, plus 3 new currencies for
-- existing account 4373) -- none are orphans, all currencies already migrated.
--
-- Usage:
--   mysql -u root count_real < backend/src/main/resources/SqlEtcForMigrate/migrate_delta_currency_domain_20260905.sql

START TRANSACTION;

CREATE TEMPORARY TABLE _map_tenant (
    old_type      ENUM('COMPANY','GROUP') NOT NULL,
    old_id        INT NOT NULL,
    new_tenant_id INT NOT NULL,
    PRIMARY KEY (old_type, old_id)
);
INSERT INTO _map_tenant (old_type, old_id, new_tenant_id)
SELECT 'COMPANY', c.id, t.id FROM c168_net_legacy_20260905.company c
JOIN tenant t ON t.tenant_type = 'COMPANY' AND t.code = c.company_id;
INSERT INTO _map_tenant (old_type, old_id, new_tenant_id)
SELECT 'GROUP', g.id, t.id FROM c168_net_legacy_20260905.groups g
JOIN tenant t ON t.tenant_type = 'GROUP' AND t.code = g.group_code;

-- =============================================================================
-- 1. currency: recompute survivor dedup over the FULL current legacy table, only insert rows whose
--    survivor id is itself new. (0 expected this round, verified.)
-- =============================================================================
CREATE TEMPORARY TABLE _map_currency (
    old_currency_id  INT NOT NULL PRIMARY KEY,
    survivor_id      INT NOT NULL
);
INSERT INTO _map_currency (old_currency_id, survivor_id)
SELECT cu.id, s.id
FROM c168_net_legacy_20260905.currency cu
JOIN (
    SELECT id, company_id, code,
           ROW_NUMBER() OVER (PARTITION BY company_id, code ORDER BY (sync_source = 'subsidiary'), id) AS rn
    FROM c168_net_legacy_20260905.currency
) s ON s.company_id = cu.company_id AND s.code = cu.code
WHERE s.rn = 1;

INSERT INTO currency (id, tenant_id, code, sync_source, status)
SELECT mc.survivor_id, m.new_tenant_id, cu.code, UPPER(cu.sync_source), 'ACTIVE'
FROM c168_net_legacy_20260905.currency cu
JOIN _map_currency mc ON mc.old_currency_id = cu.id AND mc.survivor_id = cu.id
JOIN _map_tenant m ON m.old_type = 'COMPANY' AND m.old_id = cu.company_id
WHERE mc.survivor_id NOT IN (SELECT id FROM currency);

-- =============================================================================
-- 2. account_currency: new (account, currency) pairs only. (4 expected this round.)
-- =============================================================================
INSERT INTO account_currency (account_id, tenant_id, currency_id, created_at, updated_at)
SELECT ac.account_id, cur.tenant_id, mc.survivor_id, ac.created_at, ac.updated_at
FROM c168_net_legacy_20260905.account_currency ac
JOIN _map_currency mc ON mc.old_currency_id = ac.currency_id
JOIN currency cur ON cur.id = mc.survivor_id
WHERE NOT EXISTS (
    SELECT 1 FROM account_currency x
    WHERE x.account_id = ac.account_id AND x.currency_id = mc.survivor_id
);

-- 2b. Fold account_currency_display_order into sort_order for any newly-inserted account_currency rows.
UPDATE account_currency ac
JOIN c168_net_legacy_20260905.account_currency_display_order d ON d.account_id = ac.account_id
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
-- 3. account_link: NOT id-preserved -- matched on (account_id_1, account_id_2, tenant_id, link_type),
--    scoped to legacy rows new since the 2026-09-03 state (2 expected: ids 247, 248).
-- =============================================================================
INSERT INTO account_link (account_id_1, account_id_2, tenant_id, link_type, source_account_id, created_at, updated_at)
SELECT al.account_id_1, al.account_id_2, m.new_tenant_id, UPPER(al.link_type), al.source_account_id, al.created_at, al.updated_at
FROM c168_net_legacy_20260905.account_link al
JOIN _map_tenant m ON m.old_type = 'COMPANY' AND m.old_id = al.company_id
WHERE al.id NOT IN (SELECT id FROM c168_net_legacy_20260827.account_link)
  AND NOT EXISTS (
      SELECT 1 FROM account_link x
      WHERE x.account_id_1 = al.account_id_1 AND x.account_id_2 = al.account_id_2
        AND x.tenant_id = m.new_tenant_id AND x.link_type = UPPER(al.link_type)
  );

-- =============================================================================
-- 4. tenant_feature_module: for any newly-inserted tenant only. (0 expected -- no new tenant this
--    round -- kept for structural parity / defensive backfill, no-ops via NOT EXISTS.)
-- =============================================================================
INSERT INTO tenant_feature_module (tenant_id, module_id)
SELECT t.id, 1
FROM c168_net_legacy_20260905.company c
JOIN tenant t ON t.tenant_type = 'COMPANY' AND t.code = c.company_id
WHERE c.permissions LIKE '%Games%'
  AND NOT EXISTS (SELECT 1 FROM tenant_feature_module tfm WHERE tfm.tenant_id = t.id AND tfm.module_id = 1);

INSERT INTO tenant_feature_module (tenant_id, module_id)
SELECT t.id, 2
FROM c168_net_legacy_20260905.company c
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
