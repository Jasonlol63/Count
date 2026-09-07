-- One-off DATA CORRECTION (not raw migration): backfills `user_tenant_access` rows for users who
-- had direct user-to-GROUP access in the legacy system that the original identity/tenant
-- migration (§2, `migrate_data_identity_tenant_from_legacy.sql`) never picked up.
--
-- Root cause: §2 only migrated `user_company_map` (-> `user_tenant_access`, 50 rows). Legacy also
-- has a separate, parallel table `user_group_map` (3 rows) recording users granted direct access
-- to a GROUP tenant (not via a company) -- this table was never referenced by any migration
-- script or by MIGRATION_LOG.md/TABLE_MIGRATION.md. Found by a full legacy-table inventory sweep.
--
-- Verified before writing this: all 3 rows are genuinely missing in count_real --
--   user 532 (login OK)    -> group LOL: had ZERO user_tenant_access rows at all (fully locked out)
--   user 533 (login JS_3)  -> group LOL: had only a BK1 (company) row, LOL was missing
--   user 534 (login BIN)   -> group IG : had only an RS (company) row, IG was missing
-- (LOL = group id 18 in legacy, one of the 5 real groups that make up count_real.tenant's 28 rows
-- -- not a dangling/orphan reference.)
--
-- ACL mode: new rows use the same default the rest of §2 uses for migrated access
-- (`account_acl_mode`/`process_acl_mode` = 'ALL') -- consistent with "§2 明确没做" note that all
-- migrated `user_tenant_access` rows currently use the default ALL, not a precise replica of any
-- legacy CUSTOM restriction (legacy `user_group_map` itself carries no ACL detail to replicate).
--
-- Idempotent: guarded by NOT EXISTS, safe to re-run.
--
-- Usage:
--   mysql -u root count_real < backend/src/main/resources/SqlEtcForMigrate/fix_user_group_map_backfill.sql

INSERT INTO user_tenant_access (user_id, tenant_id, account_acl_mode, process_acl_mode)
SELECT ugm.user_id, t.id, 'ALL', 'ALL'
FROM c168_net_legacy_20260827.user_group_map ugm
JOIN c168_net_legacy_20260827.groups g ON g.id = ugm.group_id
JOIN tenant t ON t.code = g.group_code AND t.tenant_type = 'GROUP'
WHERE NOT EXISTS (
    SELECT 1 FROM user_tenant_access x
    WHERE x.user_id = ugm.user_id AND x.tenant_id = t.id
);
