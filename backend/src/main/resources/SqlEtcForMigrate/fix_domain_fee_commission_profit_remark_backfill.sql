-- One-off DATA CORRECTION (not raw migration): backfills the internal `remark` bookkeeping tag
-- (DOMAIN_FEE / DOMAIN_COMMISSION / DOMAIN_NET_PROFIT) onto the 67 existing legacy-migrated
-- domain-fee-charge-family rows, matching what DomainFeeChargeServiceImpl.buildPaymentLine() now
-- writes for any freshly-created row (see MIGRATION_LOG.md §29).
--
-- Why a remark tag instead of the description-text matching used in the (reverted) §27 attempt: the
-- user's concern was that keying business logic off human-readable `description` wording is fragile --
-- if that literal text is ever tweaked, the exclusion silently stops matching with no error. A stable,
-- purpose-built tag in `remark` decouples the two: description can be reworded freely without touching
-- the C168/PROFIT exclusion logic in TransactionHistoryMapper.xml / TransactionSearchMapper.xml /
-- TransactionHistoryServiceImpl.java.
--
-- These tags are never shown to any viewer -- TransactionHistoryServiceImpl.buildDomainPaymentHistorySlice()
-- blanks the remark on any row carrying one of these before it reaches the display row, for every
-- viewer (not just C168/PROFIT) -- same convention already used for RATE middleman rows
-- (applyRateMiddlemanHistoryPresentation() does line.setRemark(null)).
--
-- Scope: 11 Fee rows (10 "PAY DOMAIN FEE" + the id=17044 AUTO_RENEW one, normalized to the same text
-- in §26) + 44 Commission rows ("% COMMISSION FROM %") + 12 Net Profit rows ("NET PROFIT FROM %",
-- 9 original + 3 backfilled in §28 for MAC999/TZX/WSMT) = 67 total, all under tenant_id=77.
--
-- Idempotent: guarded by remark IS NULL, so a row already tagged no longer matches and re-running is
-- a no-op. Safe to re-run.
--
-- Usage:
--   mysql -u root count_real < backend/src/main/resources/SqlEtcForMigrate/fix_domain_fee_commission_profit_remark_backfill.sql

UPDATE transactions
SET remark = 'DOMAIN_FEE'
WHERE tenant_id = (SELECT id FROM tenant WHERE code = 'C168' AND tenant_type = 'COMPANY')
  AND description = 'PAY DOMAIN FEE'
  AND remark IS NULL;

UPDATE transactions
SET remark = 'DOMAIN_COMMISSION'
WHERE tenant_id = (SELECT id FROM tenant WHERE code = 'C168' AND tenant_type = 'COMPANY')
  AND description LIKE '% COMMISSION FROM %'
  AND remark IS NULL;

UPDATE transactions
SET remark = 'DOMAIN_NET_PROFIT'
WHERE tenant_id = (SELECT id FROM tenant WHERE code = 'C168' AND tenant_type = 'COMPANY')
  AND description LIKE 'NET PROFIT FROM %'
  AND remark IS NULL;
