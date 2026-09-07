-- One-off DATA CORRECTION (not raw migration): swaps account_id <-> from_account_id on the 55
-- legacy-migrated "Pay Domain Fee / Auto Renew fee" and "Commission" PAYMENT rows so they match
-- the direction Spring Boot's OWN current write path (DomainFeeChargeServiceImpl.chargeDomainFee /
-- buildPaymentLine) uses — NOT a port of legacy PHP's display-layer sign-flip logic.
--
-- Background (full writeup in MIGRATION_LOG.md, to be added alongside this script): legacy PHP wrote
-- these rows with account_id = the RECEIVING side (C168's profit account for fee rows, the commission
-- recipient for commission rows) and compensated for it with a display-layer sign exception. Spring
-- Boot's chargeDomainFee() (DomainFeeChargeServiceImpl.java:139-140, :165) instead writes
-- account_id = the PAYING/losing side — payer for fee rows, C168 for commission rows — which, combined
-- with the existing plain "account_id=viewed -> negative, from_account_id=viewed -> positive" formula
-- (no per-tag exception needed), already displays correctly for any transaction it creates itself.
--
-- Verified before writing this (read-only queries, not guessed):
--   - All 64 tagged rows live under tenant_id=77 (C168), no cross-tenant surprises.
--   - Fee rows (10x DOMAIN_LIST_FEE + 1x AUTO_RENEW fee, 11 total): account_id = 4837 (C168 profit
--     account) in every single row, zero exceptions.
--   - Commission rows (40x DOMAIN_SHARE_COMMISSION + 4x AUTO_RENEW|COMMISSION, 44 total):
--     from_account_id = 4837 in every single row, zero exceptions.
--   - Net Profit rows (9 total) are NOT covered here — from_account_id is NULL there (not a clean
--     two-column swap candidate); see fix_domain_net_profit_self_reference.sql instead.
--
-- Idempotent by construction: the WHERE guards below only match rows still in the legacy direction
-- (account_id = 4837 for fee rows; from_account_id = 4837 for commission rows). Once swapped, a row no
-- longer satisfies its own guard, so re-running this script a second time is a no-op. Safe to re-run.
--
-- ⚠️ CORRECTNESS NOTE (learned the hard way — see MIGRATION_LOG.md §22 for the full incident writeup):
-- a naive single-table `SET account_id = from_account_id, from_account_id = account_id` does NOT
-- reliably swap two columns on this MariaDB server — it evaluates the second assignment against the
-- ALREADY-UPDATED value of account_id from the first assignment in the same SET list (not the row's
-- original pre-UPDATE value, despite that being MySQL's documented behavior), so it collapses both
-- columns to the same value instead of swapping them. This first version of the script shipped with
-- that bug, was run once, corrupted all 55 target rows into self-referencing rows, and had to be
-- repaired by hand (recovering the lost side from the `remark` tag's embedded account code / `AID:`
-- number, since both original values were still individually recoverable that way — do not assume that
-- escape hatch exists for a different swap). The version below uses a JOIN against a snapshot subquery
-- instead, so the SET clause reads from a separate result set unaffected by the UPDATE in progress —
-- this is the safe, portable way to swap two columns in one statement. Do not revert to the inline
-- two-column SET form.
--
-- tenant_id resolved by code (SELECT ... WHERE code='C168'), not hardcoded 77: a fresh
-- full-migration re-run (2026-09-03) proved tenant.id is not stable across runs -- see
-- fix_domain_net_profit_self_reference.sql's header for the full incident note.
--
-- Usage:
--   mysql -u root count_real < backend/src/main/resources/SqlEtcForMigrate/fix_domain_fee_commission_account_direction_swap.sql

-- 1) Fee rows: DOMAIN_LIST_FEE + the one AUTO_RENEW fee row (id=17044) — 11 rows expected.
UPDATE transactions t
JOIN (
    SELECT id, account_id, from_account_id
    FROM transactions
    WHERE tenant_id = (SELECT id FROM tenant WHERE code = 'C168' AND tenant_type = 'COMPANY')
      AND transaction_type = 'PAYMENT'
      AND account_id = 4837
      AND (
            remark LIKE '[DOMAIN_LIST_FEE|%'
         OR remark = '[DOMAIN_LIST_FEE]'
         OR (remark LIKE '[AUTO_RENEW|%' AND remark NOT LIKE '[AUTO_RENEW|COMMISSION|%' AND remark NOT LIKE '[AUTO_RENEW|NET_PROFIT|%')
      )
) src ON src.id = t.id
SET t.account_id = src.from_account_id,
    t.from_account_id = src.account_id;

-- 2) Commission rows: DOMAIN_SHARE_COMMISSION + AUTO_RENEW|COMMISSION — 44 rows expected.
UPDATE transactions t
JOIN (
    SELECT id, account_id, from_account_id
    FROM transactions
    WHERE tenant_id = (SELECT id FROM tenant WHERE code = 'C168' AND tenant_type = 'COMPANY')
      AND transaction_type = 'PAYMENT'
      AND from_account_id = 4837
      AND (
            remark LIKE '[DOMAIN_SHARE_COMMISSION|%'
         OR remark = '[DOMAIN_SHARE_COMMISSION]'
         OR remark LIKE '[AUTO_RENEW|COMMISSION|%'
      )
) src ON src.id = t.id
SET t.account_id = src.from_account_id,
    t.from_account_id = src.account_id;
