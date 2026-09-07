-- Follow-up correction to fix_rate_charge_self_referencing_from_account.sql -- that script's
-- approach was WRONG and this reverts it, replacing with the actually-correct fix.
--
-- What went wrong: fix_rate_charge_self_referencing_from_account.sql set these 21 rows'
-- from_account_id to the real middleman_account_id (from transactions_rate), reasoning that a
-- proper To/From pair was needed for the CR/DR formula to compute a non-zero amount. That was
-- correct in isolation but ignored something discovered only after testing in the browser: EVERY
-- one of these 21 "payer" rows already has an immediately-preceding sibling row (id - 1) that is
-- itself a single-sided credit to the SAME middleman account (account_id = middleman,
-- from_account_id IS NULL, same date/amount/description) -- verified for all 21. Legacy already
-- represented this fee as TWO independent rows: one single-sided credit to the middleman, one
-- (originally self-referencing, now wrongly patched to point at the middleman too) debit to the
-- payer. Pointing the payer row's from_account_id at the middleman double-credits the middleman
-- when their own Payment History is viewed: their pre-existing sibling row already gives them
-- +amount, and now the "fixed" payer row's ELSE branch gives them ANOTHER +amount for the same
-- fee. Confirmed in the browser: account 5496 (BC009's middleman) showed two 105.00 lines instead
-- of one.
--
-- Correct fix: the payer row doesn't need a from_account_id at all -- it should be a single-sided
-- DEBIT (account_id = payer, from_account_id = NULL), exactly mirroring the sibling credit row's
-- shape. The existing CR/DR formula's plain "account_id = viewing account -> -amount" branch
-- already gives the payer the correct -amount with from_account_id NULL, same as before this
-- correction -- no formula change needed for the payer side. What DOES still need a code fix is the
-- middleman's own pre-existing sibling row's sign (see fix_legacy_rate_middleman_credit_sign
-- migration note / TransactionHistoryMapper.xml, TransactionSearchMapper.xml changes) -- that's a
-- separate, pre-existing bug unrelated to this revert.
--
-- Idempotent: only touches rows currently pointing at a middleman account for one of these 22 known
-- ids; safe to re-run (second run is a no-op since from_account_id will already be NULL).
--
-- Usage:
--   mysql -u root count_real < backend/src/main/resources/SqlEtcForMigrate/fix_rate_charge_self_referencing_from_account_v2.sql

UPDATE transactions
SET from_account_id = NULL
WHERE id IN (
    5354, 5941, 7045, 7481, 8633, 8661, 9231, 9759, 10110, 11418, 11754,
    13849, 14867, 15007, 15201, 15635, 16347, 16413, 17299, 17819, 18147
)
  AND transaction_type = 'RATE';
