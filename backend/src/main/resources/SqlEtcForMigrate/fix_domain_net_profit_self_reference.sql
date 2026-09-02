-- One-off DATA CORRECTION (not raw migration): backfills from_account_id = account_id (self-reference)
-- on the 9 legacy-migrated "Net Profit" PAYMENT rows (DOMAIN_NET_PROFIT / AUTO_RENEW|NET_PROFIT), so
-- they match how Spring Boot's own chargeDomainFee() writes a fresh Net Profit line
-- (DomainFeeChargeServiceImpl.java:176: buildPaymentLine(c168TenantId, profitAccountId,
-- profitAccountId, ...) -- both account_id and from_account_id set to the SAME account, C168's own
-- profit account) rather than the legacy shape of account_id=4837 / from_account_id=NULL.
--
-- Why this makes the display correct without touching TransactionHistoryMapper.xml: the existing
-- "account_id = from_account_id -> 0" self-reference rule (added in the §16/§17 RATE fixes) already
-- zeroes out any self-referencing row in Cr/Dr -- exactly the effect legacy achieved with its explicit
-- "isDomainNetProfit -> cr_dr = 0" branch, just via a different (already-implemented) mechanism. No new
-- display-layer exception needed once the row's shape matches.
--
-- Verified before writing this: all 9 rows (8x DOMAIN_NET_PROFIT + 1x AUTO_RENEW|NET_PROFIT) have
-- account_id = 4837 (C168 profit account) and from_account_id IS NULL, under tenant_id=77, zero
-- exceptions. See fix_domain_fee_commission_account_direction_swap.sql for the companion fix covering
-- the Fee/Commission rows from the same tagged batch.
--
-- Idempotent: the WHERE guard (from_account_id IS NULL) only matches rows not yet fixed; once backfilled
-- a row no longer satisfies it, so re-running this script a second time is a no-op. Safe to re-run.
--
-- Usage:
--   mysql -u root count_real < backend/src/main/resources/SqlEtcForMigrate/fix_domain_net_profit_self_reference.sql

UPDATE transactions
SET from_account_id = account_id
WHERE tenant_id = 77
  AND transaction_type = 'PAYMENT'
  AND account_id = 4837
  AND from_account_id IS NULL
  AND (
        remark LIKE '[DOMAIN_NET_PROFIT|%'
     OR remark LIKE '[AUTO_RENEW|NET_PROFIT|%'
  );
