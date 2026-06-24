-- Migrate existing testcount data from lowercase ENUM values to uppercase.
-- Run once against your dev DB before restarting the Java backend.
-- Example: mysql -u root testcount < backend/src/main/resources/sql/migrate_enums_to_uppercase.sql

USE testcount;

-- ---------------------------------------------------------------------------
-- 1. Widen ENUM columns to VARCHAR so data can be rewritten safely
-- ---------------------------------------------------------------------------
ALTER TABLE owner MODIFY status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE tenant MODIFY tenant_type VARCHAR(20) NOT NULL;
ALTER TABLE tenant MODIFY status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE feature_module MODIFY status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE user MODIFY role VARCHAR(50) NOT NULL;
ALTER TABLE user MODIFY status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE account MODIFY status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
-- Optional tables (skip if not created yet):
-- ALTER TABLE currency MODIFY sync_source VARCHAR(20) NOT NULL DEFAULT 'MANUAL';
-- ALTER TABLE announcements MODIFY status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
-- ALTER TABLE announcements MODIFY user_type VARCHAR(20) NOT NULL DEFAULT 'USER';

-- ---------------------------------------------------------------------------
-- 2. Rewrite values to uppercase (legacy lowercase + customer service)
-- ---------------------------------------------------------------------------
UPDATE owner SET status = UPPER(status);
UPDATE tenant SET tenant_type = UPPER(tenant_type), status = UPPER(status);
UPDATE feature_module SET status = UPPER(status);
UPDATE account SET status = UPPER(status);
-- UPDATE currency SET sync_source = UPPER(sync_source);
-- UPDATE announcements SET status = UPPER(status), user_type = UPPER(user_type);

UPDATE user SET status = UPPER(status);
UPDATE user SET role = CASE LOWER(TRIM(role))
    WHEN 'admin' THEN 'ADMIN'
    WHEN 'manager' THEN 'MANAGER'
    WHEN 'supervisor' THEN 'SUPERVISOR'
    WHEN 'accountant' THEN 'ACCOUNTANT'
    WHEN 'audit' THEN 'AUDIT'
    WHEN 'customer service' THEN 'CUSTOMER_SERVICE'
    WHEN 'partnership' THEN 'PARTNERSHIP'
    ELSE UPPER(REPLACE(role, ' ', '_'))
END;

-- ---------------------------------------------------------------------------
-- 3. Restore ENUM definitions (uppercase only)
-- ---------------------------------------------------------------------------
ALTER TABLE owner MODIFY status ENUM('ACTIVE', 'INACTIVE') NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE tenant MODIFY tenant_type ENUM('GROUP', 'COMPANY') NOT NULL;
ALTER TABLE tenant MODIFY status ENUM('ACTIVE', 'INACTIVE') NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE feature_module MODIFY status ENUM('ACTIVE', 'INACTIVE') NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE user MODIFY role ENUM(
    'ADMIN', 'MANAGER', 'SUPERVISOR', 'ACCOUNTANT', 'AUDIT', 'CUSTOMER_SERVICE', 'PARTNERSHIP'
) NOT NULL;
ALTER TABLE user MODIFY status ENUM('ACTIVE', 'INACTIVE') NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE account MODIFY status ENUM('ACTIVE', 'INACTIVE') NOT NULL DEFAULT 'ACTIVE';
-- ALTER TABLE currency MODIFY sync_source ENUM('MANUAL', 'SUBSIDIARY') NOT NULL DEFAULT 'MANUAL';
-- ALTER TABLE announcements MODIFY status ENUM('ACTIVE', 'INACTIVE') NOT NULL DEFAULT 'ACTIVE';
-- ALTER TABLE announcements MODIFY user_type ENUM('USER', 'OWNER') NOT NULL DEFAULT 'USER';
