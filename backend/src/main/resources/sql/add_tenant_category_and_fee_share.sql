-- DEPRECATED — do not run on new databases.
--
-- Legacy script previously added JSON columns on tenant:
--   tenant.category_code
--   tenant.fee_share_allocate / fee_share_allocations
--
-- Use instead:
--   migrate_tenant_category_to_feature_module.sql  (category_code -> tenant_feature_module)
--   migrate_tenant_and_domain_fee_normalized.sql     (fee share + domain fee normalization)
--
-- Fresh installs: apply sql/schema.sql only.

SELECT 'add_tenant_category_and_fee_share.sql is deprecated; use migrate_* scripts instead' AS info;
