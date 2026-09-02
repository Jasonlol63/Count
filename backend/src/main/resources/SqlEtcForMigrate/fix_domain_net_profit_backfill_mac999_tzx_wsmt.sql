-- One-off DATA CORRECTION (not raw migration): backfills 3 missing Net Profit transactions for
-- MAC999/TZX/WSMT under C168 (tenant_id=77) -- see MIGRATION_LOG.md §28 for the full writeup.
--
-- Root cause (confirmed, not guessed): legacy PHP's history_api.php has a function literally named
-- buildVirtualDomainNetProfitHistory() (line 962) that, when no real [DOMAIN_NET_PROFIT|...]-tagged
-- transaction row exists for a company, computes one ON THE FLY as (that day's Fee total - Commission
-- total) and splices it into the displayed rows -- it is never written back to the `transactions`
-- table. Confirmed via broad search (not just exact tag match) that MAC999/TZX/WSMT have zero
-- DOMAIN_NET_PROFIT-tagged rows in the 2026-08-27 legacy backup -- this was never real stored data in
-- legacy either, old and new systems alike never had a row here; the old UI was just computing and
-- displaying a number that was never persisted.
--
-- Why backfill rather than replicate the "virtual" fallback in Spring Boot: the new backend's own
-- chargeDomainFee() ALWAYS writes a real Net Profit row whenever profit > 0 (DomainFeeChargeServiceImpl.java
-- :175-178) -- there is no "compute on the fly if missing" concept in the new system at all. Backfilling
-- real rows for these 3 companies makes count_real consistent with that invariant (every completed
-- domain-fee charge has an explicit Net Profit row), rather than growing new code to replicate a legacy
-- display quirk.
--
-- Amounts: Fee (2400) - Commission total (720 = 2x Sales@240 + 2x CS@120) = 1680 for all three,
-- confirmed identical to every other company that HAS a real Net Profit row in this batch pattern.
--
-- Field values: account_id = from_account_id = 4837 (C168's own PROFIT account, self-referencing --
-- matches DomainFeeChargeServiceImpl.java:180's buildPaymentLine(profitAccountId, profitAccountId, ...)
-- and how the other 9 real Net Profit rows are shaped after §22's fix). transaction_date/created_by/
-- approved_by/created_at/approved_at copied from each company's own Fee transaction in the same original
-- charge batch (id=7269 MAC999, id=7274 TZX, id=7279 WSMT) -- these 3 lines are being completed as part
-- of that same historical batch, not fabricated as "now".
--
-- Idempotent: guarded by NOT EXISTS on (tenant_id, description), safe to re-run.
--
-- Usage:
--   mysql -u root count_real < backend/src/main/resources/SqlEtcForMigrate/fix_domain_net_profit_backfill_mac999_tzx_wsmt.sql

INSERT INTO transactions (
    tenant_id, transaction_type, account_id, from_account_id, currency_id, amount,
    transaction_date, description, remark, created_by, approval_status, approved_by, approved_at,
    created_at, updated_at
)
SELECT
    77, 'PAYMENT', 4837, 4837, src.currency_id, 1680.00,
    src.transaction_date, CONCAT('NET PROFIT FROM ', src.payer_code), NULL,
    src.created_by, 'APPROVED', src.created_by, src.approved_at,
    src.created_at, src.created_at
FROM (
    SELECT 'MAC999' AS payer_code, transaction_date, created_by, approved_at, created_at, currency_id
    FROM transactions WHERE id = 7269
    UNION ALL
    SELECT 'TZX', transaction_date, created_by, approved_at, created_at, currency_id
    FROM transactions WHERE id = 7274
    UNION ALL
    SELECT 'WSMT', transaction_date, created_by, approved_at, created_at, currency_id
    FROM transactions WHERE id = 7279
) src
WHERE NOT EXISTS (
    SELECT 1 FROM transactions x
    WHERE x.tenant_id = 77
      AND x.description = CONCAT('NET PROFIT FROM ', src.payer_code)
);
