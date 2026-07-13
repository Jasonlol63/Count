-- Complete partial migration on testcount (safe to re-run).
USE testcount;

START TRANSACTION;

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

SET @has_domain_fee_settings := (
  SELECT COUNT(*)
  FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'domain_list_fee_settings'
);

SET @drop_domain_fee_settings_sql := IF(
  @has_domain_fee_settings > 0,
  'DROP TABLE domain_list_fee_settings',
  'SELECT ''domain_list_fee_settings already dropped'' AS info'
);

PREPARE drop_domain_fee_settings_stmt FROM @drop_domain_fee_settings_sql;
EXECUTE drop_domain_fee_settings_stmt;
DEALLOCATE PREPARE drop_domain_fee_settings_stmt;

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
