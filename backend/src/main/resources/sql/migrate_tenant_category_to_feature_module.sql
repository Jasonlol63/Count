-- Migrate tenant.category_code JSON -> tenant_feature_module, then drop the JSON column.
-- Safe to run once on existing testcount DBs with data.

USE testcount;

START TRANSACTION;

-- 1) Ensure canonical feature_module dictionary (idempotent)
INSERT INTO feature_module (id, code, name, sort_order, status) VALUES
  (1, 'GAME',  'Games', 1, 'ACTIVE'),
  (2, 'BANK',  'Bank',  2, 'ACTIVE'),
  (3, 'LOAN',  'Loan',  3, 'ACTIVE'),
  (4, 'RATE',  'Rate',  4, 'ACTIVE'),
  (5, 'MONEY', 'Money', 5, 'ACTIVE')
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  sort_order = VALUES(sort_order),
  status = VALUES(status);

-- 2) Copy legacy JSON values into tenant_feature_module (skip existing pairs)
INSERT IGNORE INTO tenant_feature_module (tenant_id, module_id)
SELECT t.id, fm.id
FROM tenant t
JOIN feature_module fm ON fm.status = 'ACTIVE'
WHERE t.category_code IS NOT NULL
  AND JSON_VALID(t.category_code)
  AND (
    JSON_CONTAINS(t.category_code, JSON_QUOTE(fm.name))
    OR JSON_CONTAINS(t.category_code, JSON_QUOTE(fm.code))
    OR JSON_CONTAINS(t.category_code, JSON_QUOTE(LOWER(fm.code)))
  );

-- 3) Drop JSON column (only if still present)
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

-- 4) Align tenant_feature_module FK + comment with latest schema
ALTER TABLE tenant_feature_module
  DROP FOREIGN KEY fk_tfm_module;

ALTER TABLE tenant_feature_module
  ADD CONSTRAINT fk_tfm_module
    FOREIGN KEY (module_id) REFERENCES feature_module (id) ON DELETE CASCADE;

ALTER TABLE tenant_feature_module
  COMMENT = 'Tenant business modules (replaces tenant.category_code JSON)';

COMMIT;
