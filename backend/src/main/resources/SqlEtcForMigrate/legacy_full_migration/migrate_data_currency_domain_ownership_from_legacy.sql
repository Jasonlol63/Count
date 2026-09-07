-- One-off DATA migration (not DDL): Currency / Domain / Ownership domain, legacy PHP DB -> Spring
-- Boot tenant model. See migrate_data_identity_tenant_from_legacy.sql (run first -- this script
-- depends on `tenant`/`owner`/`user`/`account` already being populated) for the overall approach.
--
-- Source: staging DB with the raw c168.net mysqldump (`c168_net_legacy_20260827` below).
-- Target: same DB the identity/tenant script wrote to (owner/tenant/account/user IDs preserved
--         1:1 from legacy; tenant IDs are the NEW merged ones from that script's run).
--
-- Scope: currency, account_currency (+ account_currency_display_order folded into sort_order),
--        domain_list_fee_price, announcements, maintenance_marquee, tenant_ownership,
--        tenant_ownership_history, tenant_fee_share_allocation, account_link, tenant_auto_renew.
--
-- Deliberately NOT migrated / deferred (see conversation for full reasoning):
--   - domain_list_fee_settings.price/maintenance_fee/group_price/company_price: superseded by the
--     newer company_period_prices/group_period_prices JSON columns (same row); those two are the
--     only source used for domain_list_fee_price.
--   - tenant_auto_renew_transaction: needs `transactions.id` to exist (Transactions domain not
--     migrated yet). company_auto_renew_request.from_account_id/to_account_id/transaction_id/
--     reject_reason have no column in the new `tenant_auto_renew` at all -- not migrated.
--   - account_currency_display_order: 11 of 16 rows have a negative/sentinel account_id that
--     doesn't correspond to any real account.id (not a real per-account preference -- looks like
--     leftover per-company default rows keyed by a JSON object instead of a plain array). Only the
--     5 rows with a real positive account_id and a flat JSON array are folded into
--     account_currency.sort_order (array index = sort order; max array length observed is 4, so a
--     fixed 0..3 unwrap covers every row with no loop/JSON_TABLE needed).
--
-- Ambiguous-but-resolved judgment calls:
--   - company_ownership/group_ownership.owner_type='account' is a defined enum value in the legacy
--     schema but 0 rows actually use it (verified) -- new schema's tenant_ownership.owner_type
--     enum('owner','user','group') has no 'account' option and none is needed.
--   - *_ownership_history.saved_by is a legacy INT with no discriminator column for which table it
--     refers to; new tenant_ownership_history.saved_by is a VARCHAR login_id/owner_code. Resolved
--     via COALESCE(owner.owner_code, user.login_id) matched by id -- safe here because legacy
--     owner.id (3-169) and user.id (216+) ranges never overlap in this dataset.
--   - currency/account_link/company_ownership scope_type ('company' vs 'group'): ignored throughout,
--     company_id column is authoritative in every row observed (same finding as the identity script).
--
-- Idempotency: NOT idempotent, single run against the state left by the identity/tenant script.
--
-- Usage:
--   mysql -u root count_real < backend/src/main/resources/sql/migrate_data_currency_domain_ownership_from_legacy.sql

-- =============================================================================
-- 0. Temp mapping table (re-derived by business code; independent of the identity script's session)
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

-- =============================================================================
-- 1. currency (scope_type ignored; company_id authoritative for both 'company' and 'group' rows).
--    Legacy has a real duplicate pattern: the SAME company+code can appear twice -- once
--    manually added (scope_type='company', sync_source='manual') and once auto-synced down from
--    the parent group (scope_type='group', sync_source='subsidiary'). New schema allows only one
--    row per (tenant_id, code), so dedupe: manual wins over subsidiary, lowest id breaks ties.
--    `_map_currency` remaps every legacy currency.id (including the dropped duplicate) to whichever
--    id survived, so account_currency below points at a row that actually exists.
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
JOIN _map_tenant m ON m.old_type = 'COMPANY' AND m.old_id = cu.company_id;

-- =============================================================================
-- 2. account_currency: legacy account_currency has NO tenant column at all -- tenant is inherited
--    through the currency row it references (currency is itself tenant-scoped). currency_id is
--    remapped through _map_currency in case account_currency pointed at a dropped duplicate.
-- =============================================================================
INSERT IGNORE INTO account_currency (account_id, tenant_id, currency_id, created_at, updated_at)
SELECT ac.account_id, cur.tenant_id, mc.survivor_id, ac.created_at, ac.updated_at
FROM c168_net_legacy_20260827.account_currency ac
JOIN _map_currency mc ON mc.old_currency_id = ac.currency_id
JOIN currency cur ON cur.id = mc.survivor_id;

-- 2b. Fold account_currency_display_order into account_currency.sort_order (only the 5 rows with a
--     real account_id and a flat JSON array; array index = display order, 1-based to match UI).
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
  AND JSON_TYPE(d.currency_order) = 'ARRAY';

-- =============================================================================
-- 3. domain_list_fee_price: unwrap the single domain_list_fee_settings row's
--    company_period_prices / group_period_prices JSON (5 known period keys, no loop needed).
--    renewal_period dictionary is already seeded by schema.sql -- not touched here.
-- =============================================================================
INSERT INTO domain_list_fee_price (tenant_type, period, price)
SELECT 'COMPANY', p.period,
       CAST(JSON_UNQUOTE(JSON_EXTRACT(s.company_period_prices, CONCAT('$."', p.period, '"'))) AS DECIMAL(25,8))
FROM c168_net_legacy_20260827.domain_list_fee_settings s
CROSS JOIN (
    SELECT '7days' AS period UNION ALL SELECT '1month' UNION ALL SELECT '3months'
    UNION ALL SELECT '6months' UNION ALL SELECT '1year'
) p
WHERE JSON_UNQUOTE(JSON_EXTRACT(s.company_period_prices, CONCAT('$."', p.period, '"'))) IS NOT NULL
  AND JSON_UNQUOTE(JSON_EXTRACT(s.company_period_prices, CONCAT('$."', p.period, '"'))) != 'null';

INSERT INTO domain_list_fee_price (tenant_type, period, price)
SELECT 'GROUP', p.period,
       CAST(JSON_UNQUOTE(JSON_EXTRACT(s.group_period_prices, CONCAT('$."', p.period, '"'))) AS DECIMAL(25,8))
FROM c168_net_legacy_20260827.domain_list_fee_settings s
CROSS JOIN (
    SELECT '7days' AS period UNION ALL SELECT '1month' UNION ALL SELECT '3months'
    UNION ALL SELECT '6months' UNION ALL SELECT '1year'
) p
WHERE JSON_UNQUOTE(JSON_EXTRACT(s.group_period_prices, CONCAT('$."', p.period, '"'))) IS NOT NULL
  AND JSON_UNQUOTE(JSON_EXTRACT(s.group_period_prices, CONCAT('$."', p.period, '"'))) != 'null';

-- =============================================================================
-- 4. announcements (company_code is the tenant business code string, not an FK id -- copied as-is;
--    created_by is user.id or owner.id depending on user_type, both preserved 1:1 by the identity
--    script, so no remapping needed)
-- =============================================================================
INSERT INTO announcements (id, title, content, company_code, status, created_by, user_type, created_at, updated_at)
SELECT id, title, content, company_code, UPPER(status), created_by, UPPER(user_type), created_at, updated_at
FROM c168_net_legacy_20260827.announcements;

-- =============================================================================
-- 5. maintenance_marquee (legacy label_type: 100% 'maintenance' in this dataset -- new schema has
--    no label_type column at all, confirmed safe to drop)
-- =============================================================================
INSERT INTO maintenance_marquee (id, prefix, content, company_code, status, created_by, user_type, created_at, updated_at)
SELECT id, COALESCE(prefix, ''), content, company_code, UPPER(status), created_by, UPPER(user_type), created_at, updated_at
FROM c168_net_legacy_20260827.maintenance_marquee;

-- =============================================================================
-- 6. tenant_ownership (company_ownership + group_ownership merged; owner_type='account' never
--    occurs in this dataset so no mapping needed for it; account_id=0 sentinel -> NULL;
--    partner_group_id varchar code -> partner_tenant_id via tenant.code lookup)
-- =============================================================================
INSERT INTO tenant_ownership (tenant_id, account_id, owner_type, partner_tenant_id, percentage, read_only, sort_order)
SELECT m.new_tenant_id,
       NULLIF(co.account_id, 0),
       co.owner_type,
       pt.id,
       co.percentage, co.read_only, co.sort_order
FROM c168_net_legacy_20260827.company_ownership co
JOIN _map_tenant m ON m.old_type = 'COMPANY' AND m.old_id = co.company_id
LEFT JOIN tenant pt ON pt.code = co.partner_group_id AND pt.tenant_type = 'GROUP';

INSERT INTO tenant_ownership (tenant_id, account_id, owner_type, partner_tenant_id, percentage, read_only, sort_order)
SELECT t.id,
       NULLIF(go.account_id, 0),
       go.owner_type,
       pt.id,
       go.percentage, go.read_only, go.sort_order
FROM c168_net_legacy_20260827.group_ownership go
JOIN tenant t ON t.tenant_type = 'GROUP' AND t.code = go.group_id
LEFT JOIN tenant pt ON pt.code = go.partner_group_id AND pt.tenant_type = 'GROUP';

-- =============================================================================
-- 7. tenant_ownership_history (company_ownership_history + group_ownership_history merged;
--    saved_by INT -> login_id/owner_code string, see header note)
-- =============================================================================
INSERT INTO tenant_ownership_history
    (tenant_id, effective_month, account_id, owner_type, partner_tenant_id, percentage, read_only, saved_by, saved_at)
SELECT m.new_tenant_id, coh.effective_month,
       NULLIF(coh.account_id, 0),
       coh.owner_type,
       pt.id,
       coh.percentage, coh.read_only,
       COALESCE(ow.owner_code, u.login_id),
       coh.saved_at
FROM c168_net_legacy_20260827.company_ownership_history coh
JOIN _map_tenant m ON m.old_type = 'COMPANY' AND m.old_id = coh.company_id
LEFT JOIN tenant pt ON pt.code = coh.partner_group_id AND pt.tenant_type = 'GROUP'
LEFT JOIN owner ow ON ow.id = coh.saved_by
LEFT JOIN user u ON u.id = coh.saved_by;

INSERT INTO tenant_ownership_history
    (tenant_id, effective_month, account_id, owner_type, partner_tenant_id, percentage, read_only, saved_by, saved_at)
SELECT t.id, goh.effective_month,
       NULLIF(goh.account_id, 0),
       goh.owner_type,
       pt.id,
       goh.percentage, goh.read_only,
       COALESCE(ow.owner_code, u.login_id),
       goh.saved_at
FROM c168_net_legacy_20260827.group_ownership_history goh
JOIN tenant t ON t.tenant_type = 'GROUP' AND t.code = goh.group_id
LEFT JOIN tenant pt ON pt.code = goh.partner_group_id AND pt.tenant_type = 'GROUP'
LEFT JOIN owner ow ON ow.id = goh.saved_by
LEFT JOIN user u ON u.id = goh.saved_by;

-- =============================================================================
-- 8. tenant_fee_share_allocation (company/groups.fee_share_allocations JSON; max 2 items per
--    category in this dataset -- fixed 0..1 unwrap, no loop needed)
-- =============================================================================
INSERT INTO tenant_fee_share_allocation (tenant_id, share_type, account_id, owner_type, percentage, sort_order)
SELECT m.new_tenant_id, cat.share_type,
       CAST(JSON_UNQUOTE(JSON_EXTRACT(c.fee_share_allocations, CONCAT('$.', cat.json_key, '[', idx.i, '].account_id'))) AS UNSIGNED),
       'owner',
       CAST(JSON_UNQUOTE(JSON_EXTRACT(c.fee_share_allocations, CONCAT('$.', cat.json_key, '[', idx.i, '].percentage'))) AS DECIMAL(7,4)),
       idx.i
FROM c168_net_legacy_20260827.company c
JOIN _map_tenant m ON m.old_type = 'COMPANY' AND m.old_id = c.id
CROSS JOIN (
    SELECT 'SALES' AS share_type, 'sales' AS json_key
    UNION ALL SELECT 'CS', 'cs'
    UNION ALL SELECT 'IT', 'it'
    UNION ALL SELECT 'PROFIT', 'profit'
) cat
CROSS JOIN (SELECT 0 AS i UNION ALL SELECT 1) idx
WHERE c.fee_share_allocations IS NOT NULL
  AND JSON_EXTRACT(c.fee_share_allocations, CONCAT('$.', cat.json_key, '[', idx.i, '].account_id')) IS NOT NULL;

INSERT INTO tenant_fee_share_allocation (tenant_id, share_type, account_id, owner_type, percentage, sort_order)
SELECT t.id, cat.share_type,
       CAST(JSON_UNQUOTE(JSON_EXTRACT(g.fee_share_allocations, CONCAT('$.', cat.json_key, '[', idx.i, '].account_id'))) AS UNSIGNED),
       'owner',
       CAST(JSON_UNQUOTE(JSON_EXTRACT(g.fee_share_allocations, CONCAT('$.', cat.json_key, '[', idx.i, '].percentage'))) AS DECIMAL(7,4)),
       idx.i
FROM c168_net_legacy_20260827.groups g
JOIN tenant t ON t.tenant_type = 'GROUP' AND t.code = g.group_code
CROSS JOIN (
    SELECT 'SALES' AS share_type, 'sales' AS json_key
    UNION ALL SELECT 'CS', 'cs'
    UNION ALL SELECT 'IT', 'it'
    UNION ALL SELECT 'PROFIT', 'profit'
) cat
CROSS JOIN (SELECT 0 AS i UNION ALL SELECT 1) idx
WHERE g.fee_share_allocations IS NOT NULL
  AND JSON_EXTRACT(g.fee_share_allocations, CONCAT('$.', cat.json_key, '[', idx.i, '].account_id')) IS NOT NULL;

-- =============================================================================
-- 9. account_link (scope_type is 100% 'company' in this dataset -- company_id authoritative)
-- =============================================================================
INSERT INTO account_link (account_id_1, account_id_2, tenant_id, link_type, source_account_id, created_at, updated_at)
SELECT al.account_id_1, al.account_id_2, m.new_tenant_id, UPPER(al.link_type), al.source_account_id, al.created_at, al.updated_at
FROM c168_net_legacy_20260827.account_link al
JOIN _map_tenant m ON m.old_type = 'COMPANY' AND m.old_id = al.company_id;

-- =============================================================================
-- 10. tenant_auto_renew (from company_auto_renew_request; entity_type company/group -> tenant_id;
--     from_account_id/to_account_id/transaction_id/reject_reason have no home in the new schema and
--     are not migrated -- see header note. tenant_auto_renew_transaction rows deferred until the
--     Transactions domain is migrated.)
-- =============================================================================
INSERT INTO tenant_auto_renew (tenant_id, expiration_snapshot, status, period, price, new_expiration_date, processed_by, processed_at, created_at, updated_at)
SELECT m.new_tenant_id, car.expiration_snapshot, car.status, car.period, car.price, car.new_expiration_date, car.processed_by, car.processed_at, car.created_at, car.updated_at
FROM c168_net_legacy_20260827.company_auto_renew_request car
JOIN _map_tenant m ON (
    (car.entity_type = 'company' AND m.old_type = 'COMPANY' AND m.old_id = car.company_id)
    OR (car.entity_type = 'group' AND m.old_type = 'GROUP' AND m.old_id = car.group_id)
);

DROP TEMPORARY TABLE _map_tenant;
DROP TEMPORARY TABLE _map_currency;
