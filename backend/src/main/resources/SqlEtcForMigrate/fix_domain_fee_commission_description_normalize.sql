-- One-off DATA CORRECTION (not raw migration), follow-up to fix_domain_fee_commission_account_direction_swap.sql
-- and fix_domain_net_profit_self_reference.sql (§22): normalizes `description` on the legacy-migrated
-- Commission / Net Profit rows to the exact text Spring Boot's own DomainFeeChargeServiceImpl.buildPaymentLine
-- writes for a freshly-created row, and clears `remark` to NULL on all 64 tagged rows (Fee + Commission +
-- Net Profit) -- Spring Boot's chargeDomainFee() never sets remark (always NULL, see
-- DomainFeeChargeServiceImpl.java buildPaymentLine()), the `[DOMAIN_LIST_FEE|...]` /
-- `[DOMAIN_SHARE_COMMISSION|...]` / `[AUTO_RENEW|...]` tag convention is legacy PHP's own bookkeeping
-- mechanism, not something the new backend reads or writes.
--
-- Explicit product decision (per user instruction 2026-09-02): do NOT port legacy's remark-tag parsing
-- (historyResolveDomainShareRoleLabel / historyResolveAutoRenewCommissionSourceCompany) into
-- TransactionHistoryServiceImpl.java. Instead, normalize the OLD data's `description` text to match what
-- the CURRENT Spring Boot write path already produces, so the EXISTING `domainProductFromDescription()`
-- string-matching logic (TransactionHistoryServiceImpl.java:391-416) resolves ID PRODUCT correctly with
-- zero code changes -- consistent with §22's account_id/from_account_id fix (fix data to match the new
-- convention, don't grow the display layer to match old data).
--
-- Why the OLD `description` text can't just be reused as-is: for the 40 DOMAIN_SHARE_COMMISSION rows
-- (not the 4 AUTO_RENEW|COMMISSION ones), the stored description is a legacy bug in its own right --
-- it's hardcoded to read "... Commision for K" for every single row regardless of which company actually
-- paid the domain fee (verified: MAC999/TZX/WSMT/95/AG/RS/WCC/BP17/X17/23/UG rows all say "for K", not
-- their own company code) -- K happens to be who processes these in the legacy admin UI, not the payer.
-- The real payer code only survives in the `remark` tag (e.g. `[DOMAIN_SHARE_COMMISSION|AG|ROLE:SALES|AID:4841]`
-- -> payer=AG), which is why this script reads `remark` as the source of truth for role+payer before
-- clearing it, rather than trying to fix up the existing description text in place.
--
-- New format used (verbatim from DomainFeeChargeServiceImpl.java:167 and :177):
--   Commission:  "{SALES|CS|IT|PROFIT} COMMISSION FROM {payerCode}"
--   Net Profit:  "NET PROFIT FROM {payerCode}"
--
-- Scope NOT touched here:
--   - The 10 DOMAIN_LIST_FEE rows' description ("Pay Domain Fee") already case-insensitively matches
--     `domainProductFromDescription()`'s `d.startsWith("PAY DOMAIN FEE")` check as-is -- left alone.
--
-- ⚠️ UPDATE (see MIGRATION_LOG.md §26): the 1 AUTO_RENEW fee row (id=17044) was originally left with
-- its legacy description "Renew AJ | 1 year" untouched here, on the assumption that Spring Boot has no
-- write path for "auto renew" fee charges distinct from "domain list" fee charges. That assumption was
-- wrong -- DomainFeeChargeServiceImpl.chargeDomainFee() is the ONLY fee-charging code path in the new
-- backend and it always writes the literal "PAY DOMAIN FEE" regardless of whether the charge originated
-- from a listing or a renewal (legacy PHP's own display layer independently arrives at the same
-- "Pay Domain Fee" text for AUTO_RENEW-tagged rows too, via historyIsAutoRenewFeeSms() folding into the
-- same isDomainListFee branch). id=17044 has since been fixed by hand to description = 'PAY DOMAIN FEE'
-- to match; statement 4 below covers it going forward so a fresh run of this script from a clean
-- migration reaches the same end state without a manual step.
--
-- Idempotent: description rewrite is guarded by remark IS NOT NULL (a row already normalized has
-- remark = NULL and no longer matches), so this is safe to re-run.
--
-- Usage:
--   mysql -u root count_real < backend/src/main/resources/SqlEtcForMigrate/fix_domain_fee_commission_description_normalize.sql

-- 1) Commission rows (44 expected): rebuild description from remark's ROLE:/payer segments.
UPDATE transactions t
JOIN (
    SELECT
        id,
        CONCAT(
            REPLACE(REGEXP_SUBSTR(remark, 'ROLE:[A-Z]+'), 'ROLE:', ''),
            ' COMMISSION FROM ',
            REPLACE(
                CASE
                    WHEN remark LIKE '[AUTO_RENEW|COMMISSION|%'
                        THEN SUBSTRING_INDEX(SUBSTRING_INDEX(remark, '|', 3), '|', -1)
                    ELSE SUBSTRING_INDEX(SUBSTRING_INDEX(remark, '|', 2), '|', -1)
                END,
                ']', ''
            )
        ) AS new_description
    FROM transactions
    WHERE tenant_id = (SELECT id FROM tenant WHERE code = 'C168' AND tenant_type = 'COMPANY')
      AND transaction_type = 'PAYMENT'
      AND remark IS NOT NULL
      AND (remark LIKE '[DOMAIN_SHARE_COMMISSION|%' OR remark LIKE '[AUTO_RENEW|COMMISSION|%')
) src ON src.id = t.id
SET t.description = src.new_description,
    t.remark = NULL;

-- 2) Net Profit rows (9 expected): rebuild description from remark's payer segment.
UPDATE transactions t
JOIN (
    SELECT
        id,
        CONCAT(
            'NET PROFIT FROM ',
            REPLACE(
                CASE
                    WHEN remark LIKE '[AUTO_RENEW|NET_PROFIT|%'
                        THEN SUBSTRING_INDEX(SUBSTRING_INDEX(remark, '|', 3), '|', -1)
                    ELSE SUBSTRING_INDEX(SUBSTRING_INDEX(remark, '|', 2), '|', -1)
                END,
                ']', ''
            )
        ) AS new_description
    FROM transactions
    WHERE tenant_id = (SELECT id FROM tenant WHERE code = 'C168' AND tenant_type = 'COMPANY')
      AND transaction_type = 'PAYMENT'
      AND remark IS NOT NULL
      AND (remark LIKE '[DOMAIN_NET_PROFIT|%' OR remark LIKE '[AUTO_RENEW|NET_PROFIT|%')
) src ON src.id = t.id
SET t.description = src.new_description,
    t.remark = NULL;

-- 3) Fee rows (11 expected): description left as-is (see header note); only clear remark.
UPDATE transactions
SET remark = NULL
WHERE tenant_id = (SELECT id FROM tenant WHERE code = 'C168' AND tenant_type = 'COMPANY')
  AND transaction_type = 'PAYMENT'
  AND remark IS NOT NULL
  AND (
        remark LIKE '[DOMAIN_LIST_FEE|%'
     OR remark = '[DOMAIN_LIST_FEE]'
     OR (remark LIKE '[AUTO_RENEW|%' AND remark NOT LIKE '[AUTO_RENEW|COMMISSION|%' AND remark NOT LIKE '[AUTO_RENEW|NET_PROFIT|%')
  );

-- 4) The 1 AUTO_RENEW fee row (id=17044): normalize description to match the other 10 fee rows
-- (see the header note above -- added after the fact, MIGRATION_LOG.md §26).
UPDATE transactions
SET description = 'PAY DOMAIN FEE'
WHERE tenant_id = (SELECT id FROM tenant WHERE code = 'C168' AND tenant_type = 'COMPANY')
  AND id = 17044
  AND description = 'Renew AJ | 1 year';
