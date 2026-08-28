-- One-off DATA CORRECTION (not raw migration): fixes legacy "self-transfer" Rate Charge /
-- middleman-fee transactions where account_id = from_account_id.
--
-- Background: legacy data has 22 RATE-type transactions where account_id and from_account_id are
-- the SAME account (description "Rate charge (xN) from CCY amount"). This was already the case in
-- the raw legacy `transactions` table (not introduced by migration -- verified byte-identical). The
-- new Payment History CR/DR formula (TransactionHistoryMapper.xml) has
-- `WHEN account_id = from_account_id THEN 0`, so these rows silently net to zero and the real fee
-- disappears from the account's running balance -- confirmed with the user this special-case rule
-- itself stays as-is (their call), but the underlying `from_account_id` should point at the REAL
-- middleman account instead of the fee-paying account itself, matching how the current app's own
-- RATE submission code (TransactionSubmitServiceImpl) books a Middle-Man fee: a real two-account
-- pair (payer account_id, middleman from_account_id), never self-referencing.
--
-- Source of truth: transactions_rate.middleman_account_id, already correctly populated by
-- migrate_data_transactions_from_legacy.sql from legacy transactions_rate.rate_middleman_account_id.
-- These self-charge rows don't carry a rate_group_id of their own (matches schema design -- only the
-- leg1/leg2 pair is group-tagged), so the matching header is found by (tenant_id, same calendar date
-- as the header's leg1 transaction), picking the header whose middleman_amount is closest to this
-- row's amount when a tenant has multiple RATE submissions on the same day (verified: 19/22 match
-- exactly, 1/22 differs by a sub-cent rounding artifact, 1/22 has only one same-day candidate despite
-- an amount mismatch -- still the correct match by elimination, nothing else on that tenant/date).
--
-- 1 of 22 rows (id 2917, tenant 95 / company_id 123, 2026-03-16, 108.84) has NO recoverable
-- middleman account anywhere in the legacy DB -- checked transactions_rate, transactions_rate_details,
-- and transaction_entry, all empty for this transaction. Left untouched; not fabricated.
--
-- account_id / amount / transaction_type / transaction_date / description are NOT touched -- only
-- from_account_id changes.
--
-- Idempotent: the WHERE clause only ever matches rows still self-referencing
-- (account_id = from_account_id), so re-running after the fix is applied is a no-op.
--
-- Usage:
--   mysql -u root count_real < backend/src/main/resources/SqlEtcForMigrate/fix_rate_charge_self_referencing_from_account.sql

UPDATE transactions t
SET t.from_account_id = (
    SELECT tr.middleman_account_id
    FROM transactions_rate tr
    JOIN transactions l1 ON l1.id = tr.leg1_transaction_id
    WHERE tr.tenant_id = t.tenant_id
      AND DATE(l1.transaction_date) = DATE(t.transaction_date)
      AND tr.middleman_account_id IS NOT NULL
    ORDER BY ABS(tr.middleman_amount - t.amount) ASC
    LIMIT 1
)
WHERE t.account_id = t.from_account_id
  AND t.transaction_type = 'RATE'
  AND EXISTS (
      SELECT 1
      FROM transactions_rate tr
      JOIN transactions l1 ON l1.id = tr.leg1_transaction_id
      WHERE tr.tenant_id = t.tenant_id
        AND DATE(l1.transaction_date) = DATE(t.transaction_date)
        AND tr.middleman_account_id IS NOT NULL
  );
