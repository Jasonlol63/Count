-- One-off DATA migration (not DDL): tenant_feature_module, legacy PHP DB -> Spring Boot tenant model.
--
-- Source: staging DB holding the raw 2026-08-27 c168.net mysqldump, imported as-is
--         (`c168_net_legacy_20260827` below -- adjust if your staging DB name differs).
-- Target: count_real -- requires the identity/tenant domain to already be migrated
--         (migrate_data_identity_tenant_from_legacy.sql), since this maps by tenant.code.
--
-- Bug this fixes: tenant_feature_module was never covered by the original migration scripts
-- (see MIGRATION_LOG.md), so it was left empty for every migrated tenant. hasGame/hasBank
-- (PermissionServiceImpl, gates the "Data Capture" sidebar entry and the Maintenance ->
-- Formula/Payment/Transaction Maintenance submenu, see SessionUser.buildMenu) always resolved
-- false for migrated companies, hiding those entries even for GAME-category tenants.
--
-- Source of truth: legacy `company.permissions` / `groups.permissions` (JSON string array,
-- observed values are exactly '["Games"]' or '["Bank"]', never both, never NULL for company).
-- feature_module.id: 1 = GAME, 2 = BANK (see schema.sql seed data).
--
-- Idempotency: NOT idempotent (plain INSERT, no dedup dance) -- intended for a single run after
-- the identity/tenant migration, against a tenant_feature_module table that is still empty.
--
-- Usage:
--   mysql -u root count_real < backend/src/main/resources/SqlEtcForMigrate/migrate_data_feature_module_from_legacy.sql

-- =============================================================================
-- 1. Company tenants: derive from company.permissions
-- =============================================================================
INSERT INTO tenant_feature_module (tenant_id, module_id)
SELECT t.id, 1
FROM c168_net_legacy_20260827.company c
JOIN tenant t ON t.tenant_type = 'COMPANY' AND t.code = c.company_id
WHERE c.permissions LIKE '%Games%';

INSERT INTO tenant_feature_module (tenant_id, module_id)
SELECT t.id, 2
FROM c168_net_legacy_20260827.company c
JOIN tenant t ON t.tenant_type = 'COMPANY' AND t.code = c.company_id
WHERE c.permissions LIKE '%Bank%';

-- =============================================================================
-- 2. Group tenants: always GAME (matches app convention -- DomainServiceImpl
--    ensureDefaultGroupFeatureModule / PermissionServiceImpl "group ledger is always
--    treated as a Games identity for sidebar/menu purposes"). Legacy groups.permissions
--    is redundant with this (every non-NULL row observed is '["Games"]') but not
--    otherwise consulted here.
-- =============================================================================
INSERT INTO tenant_feature_module (tenant_id, module_id)
SELECT t.id, 1
FROM tenant t
WHERE t.tenant_type = 'GROUP';
