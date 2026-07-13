-- Migrate testcount tenant + domain fee to normalized tables (preserve existing data).
-- Safe to run once on existing testcount DBs.
--
-- Example:
--   mysql -u root testcount < backend/src/main/resources/sql/migrate_tenant_and_domain_fee_normalized.sql

USE testcount;

START TRANSACTION;

-- =============================================================================
-- 1) renewal_period dictionary
-- =============================================================================
CREATE TABLE IF NOT EXISTS `renewal_period` (
  `code`       VARCHAR(20)  NOT NULL COMMENT 'Machine code e.g. 7days, 1month, 1year',
  `sort_order` SMALLINT UNSIGNED NOT NULL,
  `label`      VARCHAR(50)  NOT NULL COMMENT 'Display label',
  PRIMARY KEY (`code`),
  KEY `idx_renewal_period_sort` (`sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Auto-renew period dictionary';

INSERT INTO `renewal_period` (`code`, `sort_order`, `label`) VALUES
  ('7days',   1, '7 Days'),
  ('1month',  2, '1 Month'),
  ('3months', 3, '3 Months'),
  ('6months', 4, '6 Months'),
  ('1year',   5, '1 Year')
ON DUPLICATE KEY UPDATE
  sort_order = VALUES(sort_order),
  label = VALUES(label);

-- =============================================================================
-- 2) domain_list_fee_price (from domain_list_fee_settings JSON)
-- =============================================================================
CREATE TABLE IF NOT EXISTS `domain_list_fee_price` (
  `tenant_type` ENUM('GROUP', 'COMPANY') NOT NULL COMMENT 'GROUP or COMPANY list fee',
  `period`      VARCHAR(20)  NOT NULL COMMENT 'FK renewal_period.code',
  `price`       DECIMAL(25, 8) NOT NULL DEFAULT 0 COMMENT 'Price for this period',
  `updated_at`  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`tenant_type`, `period`),
  KEY `idx_dlfp_period` (`period`),
  CONSTRAINT `fk_dlfp_period`
    FOREIGN KEY (`period`) REFERENCES `renewal_period` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='C168 global domain list fee / auto-renew prices (normalized)';

SET @has_domain_fee_settings := (
  SELECT COUNT(*)
  FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'domain_list_fee_settings'
);

SET @migrate_domain_fee_sql := IF(
  @has_domain_fee_settings > 0,
  'INSERT INTO domain_list_fee_price (tenant_type, period, price, updated_at)
   SELECT ''COMPANY'', p.code,
          CAST(JSON_UNQUOTE(JSON_EXTRACT(s.company_price, CONCAT(''$.'', p.code))) AS DECIMAL(25, 8)),
          s.updated_at
   FROM domain_list_fee_settings s
   CROSS JOIN renewal_period p
   WHERE s.id = 1
     AND JSON_VALID(s.company_price)
     AND JSON_EXTRACT(s.company_price, CONCAT(''$.'', p.code)) IS NOT NULL
   ON DUPLICATE KEY UPDATE
     price = VALUES(price),
     updated_at = VALUES(updated_at)',
  'SELECT ''domain_list_fee_settings not found; skip company fee migration'' AS info'
);

PREPARE migrate_company_fee_stmt FROM @migrate_domain_fee_sql;
EXECUTE migrate_company_fee_stmt;
DEALLOCATE PREPARE migrate_company_fee_stmt;

SET @migrate_group_fee_sql := IF(
  @has_domain_fee_settings > 0,
  'INSERT INTO domain_list_fee_price (tenant_type, period, price, updated_at)
   SELECT ''GROUP'', p.code,
          CAST(JSON_UNQUOTE(JSON_EXTRACT(s.group_price, CONCAT(''$.'', p.code))) AS DECIMAL(25, 8)),
          s.updated_at
   FROM domain_list_fee_settings s
   CROSS JOIN renewal_period p
   WHERE s.id = 1
     AND JSON_VALID(s.group_price)
     AND JSON_EXTRACT(s.group_price, CONCAT(''$.'', p.code)) IS NOT NULL
   ON DUPLICATE KEY UPDATE
     price = VALUES(price),
     updated_at = VALUES(updated_at)',
  'SELECT ''domain_list_fee_settings not found; skip group fee migration'' AS info'
);

PREPARE migrate_group_fee_stmt FROM @migrate_group_fee_sql;
EXECUTE migrate_group_fee_stmt;
DEALLOCATE PREPARE migrate_group_fee_stmt;

-- Default zero rows only for missing pairs (do not overwrite migrated prices)
INSERT IGNORE INTO domain_list_fee_price (tenant_type, period, price)
SELECT tt.tenant_type, rp.code, 0
FROM (
  SELECT 'GROUP' AS tenant_type
  UNION ALL
  SELECT 'COMPANY'
) AS tt
CROSS JOIN renewal_period AS rp;

SET @drop_domain_fee_settings_sql := IF(
  @has_domain_fee_settings > 0,
  'DROP TABLE domain_list_fee_settings',
  'SELECT ''domain_list_fee_settings already dropped'' AS info'
);

PREPARE drop_domain_fee_settings_stmt FROM @drop_domain_fee_settings_sql;
EXECUTE drop_domain_fee_settings_stmt;
DEALLOCATE PREPARE drop_domain_fee_settings_stmt;

-- =============================================================================
-- 3) tenant_fee_share_allocation (from tenant.fee_share_allocations JSON if present)
-- =============================================================================
CREATE TABLE IF NOT EXISTS `tenant_fee_share_allocation` (
  `id`                INT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`         INT UNSIGNED NOT NULL COMMENT 'FK tenant.id',
  `share_type`        ENUM('SALES', 'CS', 'IT', 'PROFIT') NOT NULL COMMENT 'Fee share category',
  `account_id`        INT UNSIGNED DEFAULT NULL COMMENT 'Shareholder account (owner.id or user.id)',
  `owner_type`        ENUM('owner', 'user', 'group') NOT NULL DEFAULT 'owner' COMMENT 'Account identity type',
  `partner_tenant_id` INT UNSIGNED DEFAULT NULL COMMENT 'When owner_type=group, FK partner tenant.id',
  `percentage`        DECIMAL(7, 4) NOT NULL DEFAULT 0.0000 COMMENT 'Share percentage (e.g. 33.3333)',
  `sort_order`        INT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tfsa` (`tenant_id`, `share_type`, `account_id`, `owner_type`, `partner_tenant_id`),
  KEY `idx_tfsa_tenant_id` (`tenant_id`),
  CONSTRAINT `fk_tfsa_tenant`
    FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_tfsa_partner_tenant`
    FOREIGN KEY (`partner_tenant_id`) REFERENCES `tenant` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Per-tenant fee share rows (replaces tenant.fee_share_allocations JSON)';

-- Legacy JSON shapes vary; migrate only when JSON is a non-empty array of objects.
-- Expected keys (any casing): shareType/share_type, accountId/account_id, ownerType/owner_type,
-- partnerTenantId/partner_tenant_id, percentage/percent, sortOrder/sort_order.
INSERT IGNORE INTO tenant_fee_share_allocation (
  tenant_id, share_type, account_id, owner_type, partner_tenant_id, percentage, sort_order
)
SELECT
  t.id,
  UPPER(COALESCE(
    NULLIF(JSON_UNQUOTE(JSON_EXTRACT(j.row_json, '$.shareType')), 'null'),
    NULLIF(JSON_UNQUOTE(JSON_EXTRACT(j.row_json, '$.share_type')), 'null'),
    NULLIF(JSON_UNQUOTE(JSON_EXTRACT(j.row_json, '$.type')), 'null')
  )) AS share_type,
  CAST(COALESCE(
    NULLIF(JSON_UNQUOTE(JSON_EXTRACT(j.row_json, '$.accountId')), 'null'),
    NULLIF(JSON_UNQUOTE(JSON_EXTRACT(j.row_json, '$.account_id')), 'null')
  ) AS UNSIGNED) AS account_id,
  LOWER(COALESCE(
    NULLIF(JSON_UNQUOTE(JSON_EXTRACT(j.row_json, '$.ownerType')), 'null'),
    NULLIF(JSON_UNQUOTE(JSON_EXTRACT(j.row_json, '$.owner_type')), 'null'),
    'owner'
  )) AS owner_type,
  CAST(COALESCE(
    NULLIF(JSON_UNQUOTE(JSON_EXTRACT(j.row_json, '$.partnerTenantId')), 'null'),
    NULLIF(JSON_UNQUOTE(JSON_EXTRACT(j.row_json, '$.partner_tenant_id')), 'null')
  ) AS UNSIGNED) AS partner_tenant_id,
  CAST(COALESCE(
    NULLIF(JSON_UNQUOTE(JSON_EXTRACT(j.row_json, '$.percentage')), 'null'),
    NULLIF(JSON_UNQUOTE(JSON_EXTRACT(j.row_json, '$.percent')), 'null'),
    '0'
  ) AS DECIMAL(7, 4)) AS percentage,
  CAST(COALESCE(
    NULLIF(JSON_UNQUOTE(JSON_EXTRACT(j.row_json, '$.sortOrder')), 'null'),
    NULLIF(JSON_UNQUOTE(JSON_EXTRACT(j.row_json, '$.sort_order')), 'null'),
    '0'
  ) AS SIGNED) AS sort_order
FROM tenant t
JOIN JSON_TABLE(
  t.fee_share_allocations,
  '$[*]' COLUMNS (
    row_json JSON PATH '$'
  )
) AS j
WHERE t.fee_share_allocations IS NOT NULL
  AND JSON_VALID(t.fee_share_allocations)
  AND JSON_TYPE(t.fee_share_allocations) = 'ARRAY';

-- =============================================================================
-- 4) Align tenant table with latest schema (drop legacy JSON columns)
-- =============================================================================
SET @has_fee_share_allocations := (
  SELECT COUNT(*)
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'tenant'
    AND COLUMN_NAME = 'fee_share_allocations'
);

SET @drop_fee_share_sql := IF(
  @has_fee_share_allocations > 0,
  'ALTER TABLE tenant DROP COLUMN fee_share_allocations',
  'SELECT ''tenant.fee_share_allocations already dropped'' AS info'
);

PREPARE drop_fee_share_stmt FROM @drop_fee_share_sql;
EXECUTE drop_fee_share_stmt;
DEALLOCATE PREPARE drop_fee_share_stmt;

SET @has_category_code := (
  SELECT COUNT(*)
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'tenant'
    AND COLUMN_NAME = 'category_code'
);

SET @drop_category_sql := IF(
  @has_category_code > 0,
  'ALTER TABLE tenant DROP COLUMN category_code',
  'SELECT ''tenant.category_code already dropped'' AS info'
);

PREPARE drop_category_stmt FROM @drop_category_sql;
EXECUTE drop_category_stmt;
DEALLOCATE PREPARE drop_category_stmt;

COMMIT;
