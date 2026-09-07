-- One-off DATA CORRECTION (not raw migration): backfills the `tenant_auto_renew_transaction`
-- link rows that §3 (`migrate_data_currency_domain_ownership_from_legacy.sql`) deliberately
-- deferred until the Transactions domain was migrated (§12), but nobody ever came back to finish.
--
-- Source: legacy `company_auto_renew_request.transaction_id` -- out of the 10 legacy rows
-- (all migrated into `tenant_auto_renew`, see §3 result table), only ONE is `approved` with a
-- non-null `transaction_id`: id=2943 (company 324 / tenant code AJ), transaction_id=17044.
-- Verified: transactions.id=17044 exists in count_real (description "Renew AJ | 1 year",
-- amount 2400, tenant_id=77) -- the transaction itself was correctly carried over by §12, only
-- the link row was missing. No other legacy request has a transaction_id to backfill, and no
-- sibling commission/profit legs exist for this renewal in `transactions` (legacy only ever
-- recorded a single payment leg per request, unlike the current `chargeDomainFee` which can
-- write multiple legs per request) -- so exactly 1 row is expected out of this script.
--
-- Resolution path: legacy request -> company.company_id (business code) -> tenant.code ->
-- tenant_auto_renew via (tenant_id, expiration_snapshot) [UNIQUE uk_tenant_expiration] ->
-- transactions.id (preserved 1:1 from legacy transactions.id per §12).
--
-- Only entity_type='company' is handled -- confirmed no entity_type='group' row in legacy
-- `company_auto_renew_request` has a non-null transaction_id, so a group-side join was not
-- needed for this data set (would need `groups.group_code` -> tenant.code instead of
-- `company.company_id` if that ever appears in a future backup).
--
-- Idempotent: guarded by NOT EXISTS, safe to re-run.
--
-- Usage:
--   mysql -u root count_real < backend/src/main/resources/SqlEtcForMigrate/fix_tenant_auto_renew_transaction_backfill.sql

INSERT INTO tenant_auto_renew_transaction (request_id, transaction_id)
SELECT tar.id, car.transaction_id
FROM c168_net_legacy_20260827.company_auto_renew_request car
JOIN c168_net_legacy_20260827.company c ON car.entity_type = 'company' AND c.id = car.company_id
JOIN tenant t ON t.code = c.company_id AND t.tenant_type = 'COMPANY'
JOIN tenant_auto_renew tar ON tar.tenant_id = t.id AND tar.expiration_snapshot = car.expiration_snapshot
JOIN transactions tx ON tx.id = car.transaction_id
WHERE car.transaction_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM tenant_auto_renew_transaction x
      WHERE x.request_id = tar.id AND x.transaction_id = tx.id
  );
