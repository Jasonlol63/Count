-- =============================================================================
-- Currency module (optimized) — apply after sql/schema.sql (tenant model)
-- =============================================================================
--
-- Replaces legacy tables in resources/schema.sql:
--   currency                        → currency (tenant-scoped, single scope key)
--   account_currency                → account_currency (includes display order)
--   account_currency_display_order  → dropped (merged into account_currency)
--
-- Unchanged (still reference currency.id by FK):
--   transactions, transactions_deleted,
--   transactions_rate, transactions_rate_details,
--   transaction_entry,
--   data_captures, data_capture_details, data_capture_group_draft, process, ...
--
-- Legacy → new mapping notes:
--   currency.company_id + scope_type + scope_id  →  currency.tenant_id
--   account_currency_display_order.currency_order (JSON array of codes)
--     → account_currency.display_order per (account_id, currency_id)
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. Currency dictionary (per tenant: company or group ledger)
-- -----------------------------------------------------------------------------
CREATE TABLE `currency` (
  `id`          INT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`   INT UNSIGNED NOT NULL COMMENT 'FK tenant.id',
  `code`        VARCHAR(10)  NOT NULL COMMENT 'Business code e.g. MYR, SGD, USD, JPY',
  `name`        VARCHAR(100)          DEFAULT NULL COMMENT 'Optional display label',
  `sync_source` ENUM('MANUAL', 'SUBSIDIARY') NOT NULL DEFAULT 'MANUAL' COMMENT 'subsidiary = auto-synced into group ledger from child companies',
  `created_at`  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_currency_tenant_code` (`tenant_id`, `code`),
  KEY `idx_currency_tenant_id` (`tenant_id`),
  KEY `idx_currency_status` (`status`),
  CONSTRAINT `fk_currency_tenant`
    FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
    ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Tenant-scoped currency catalog';

-- -----------------------------------------------------------------------------
-- 2. Account ↔ currency grant + UI sort order (merged legacy pair)
--    Replaces: account_currency + account_currency_display_order
-- -----------------------------------------------------------------------------
CREATE TABLE `account_currency` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `account_id`     INT UNSIGNED NOT NULL COMMENT 'FK ledger account.id (legacy account table PK)',
  `currency_id`    INT UNSIGNED NOT NULL COMMENT 'FK currency.id',
  `display_order`  SMALLINT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'Lower value shown first in UI',
  `created_at`     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_account_currency` (`account_id`, `currency_id`),
  KEY `idx_ac_currency_id` (`currency_id`),
  KEY `idx_ac_account_display` (`account_id`, `display_order`),
  CONSTRAINT `fk_ac_currency`
    FOREIGN KEY (`currency_id`) REFERENCES `currency` (`id`)
    ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Currencies enabled per ledger account with display sort';

-- -----------------------------------------------------------------------------
-- Optional seed example (tenant code 95 must exist in tenant table)
-- -----------------------------------------------------------------------------
-- INSERT INTO currency (tenant_id, code, name, sync_source)
-- SELECT t.id, v.code, v.name, 'manual'
-- FROM tenant t
-- CROSS JOIN (
--   SELECT 'MYR' AS code, 'Malaysian Ringgit' AS name UNION ALL
--   SELECT 'SGD', 'Singapore Dollar' UNION ALL
--   SELECT 'USD', 'US Dollar'
-- ) v
-- WHERE t.code = '95'
-- ON DUPLICATE KEY UPDATE name = VALUES(name);

-- -----------------------------------------------------------------------------
-- Legacy data migration sketch (run once when cutting over from resources/schema.sql)
-- -----------------------------------------------------------------------------
-- -- 1) currency: map company/group scope → tenant_id
-- INSERT INTO currency (tenant_id, code, sync_source, status, created_at, updated_at)
-- SELECT
--   t.id,
--   UPPER(TRIM(c.code)),
--   c.sync_source,
--   'active',
--   NOW(),
--   NOW()
-- FROM legacy_currency c
-- JOIN tenant t ON (
--   (c.scope_type = 'company' AND t.tenant_type = 'company' AND t.id = c.scope_id)
--   OR (c.scope_type = 'group' AND t.tenant_type = 'group' AND t.id = c.scope_id)
-- );
--
-- -- 2) account_currency: copy rows + derive display_order from JSON order table
-- INSERT INTO account_currency (account_id, currency_id, display_order, created_at, updated_at)
-- SELECT
--   lac.account_id,
--   nc.id,
--   COALESCE(
--     (
--       SELECT ord.pos
--       FROM JSON_TABLE(
--         acdo.currency_order,
--         '$[*]' COLUMNS (pos FOR ORDINALITY, code VARCHAR(10) PATH '$')
--       ) ord
--       WHERE UPPER(ord.code) = UPPER(nc.code)
--       LIMIT 1
--     ),
--     0
--   ) - 1 AS display_order,
--   lac.created_at,
--   lac.updated_at
-- FROM legacy_account_currency lac
-- JOIN legacy_currency lc ON lc.id = lac.currency_id
-- JOIN currency nc ON nc.tenant_id = /* mapped tenant */ AND nc.code = UPPER(lc.code)
-- LEFT JOIN legacy_account_currency_display_order acdo ON acdo.account_id = lac.account_id;
