-- One-off DATA CORRECTION (not raw migration): normalizes `description` on legacy-migrated
-- PAYMENT / CLAIM / CLEAR / CONTRA transactions from the old single-sided format
-- ("{TYPE} FROM {payer}") to the two-sided format Spring Boot's own manual-transfer submit path
-- writes today ("{TYPE} FROM {payer} TO {payee}") -- see
-- TransactionSubmitServiceImpl.formatTransferDescription().
--
-- Why this is needed: TransactionHistoryServiceImpl.applyManualTransferHistoryPresentation() already
-- rewrites the description per viewer (payer's own view -> "{TYPE} TO {payee}", payee's own view ->
-- "{TYPE} FROM {payer}") -- this logic is correct and was NOT missing. But it is gated by
-- shouldRewriteManualTransferHistoryDescription(), which only fires when the stored description
-- STARTS WITH "{TYPE} FROM " AND CONTAINS " TO " (i.e. already names both parties). Legacy PHP only
-- ever stored one side ("{TYPE} FROM {payer}", no " TO " segment), so every migrated row fails that
-- gate and the raw legacy text is shown verbatim to every viewer regardless of which side they are on
-- -- this is what produced the "AG sees CONTRA FROM AG instead of CONTRA TO EXPENSES" symptom.
--
-- Fix approach (consistent with §22's domain-fee data correction, not a code change): rewrite the
-- OLD data's `description` to already include both sides, using account_id (= the "to"/payee side)
-- and from_account_id (= the "from"/payer side) — confirmed these two columns are NOT swapped for this
-- transaction family (unlike the domain-fee PAYMENT rows fixed in §22; verified against
-- TransactionSubmitServiceImpl.submitTransfer(): account_id = toAccountId, from_account_id =
-- fromAccountId, matching what's already stored). Once the description passes the gate, the EXISTING
-- display code takes over and re-derives the final per-viewer text at render time — the exact wording
-- written here doesn't matter beyond satisfying the gate, so account business codes (not display
-- names) are used for consistency with the rest of this migration's fix scripts.
--
-- Scope (verified via read-only queries before writing this, not guessed):
--   10,567 rows across 11 tenants (77,78,79,80,81,82,83,84,85,89,94) — PAYMENT/CLAIM/CLEAR/CONTRA
--   whose description matches "{TYPE} FROM %" but does NOT contain " TO ". Confirmed 0 of these rows
--   have a NULL account_id/from_account_id, and all 10,567 join cleanly to both account rows.
--   RATE-type transactions are NOT in scope here — already fixed independently in §15/§16/§17 with
--   their own two-sided description format and viewer-based rewrite.
--
-- Idempotent: the WHERE guard (description NOT LIKE '% TO %') only matches rows still in the old
-- single-sided format; once rewritten to the two-sided format, a row no longer satisfies its own
-- guard, so re-running this script a second time is a no-op. Safe to re-run.
--
-- Usage:
--   mysql -u root count_real < backend/src/main/resources/SqlEtcForMigrate/fix_manual_transfer_description_two_sided_format.sql

UPDATE transactions t
JOIN (
    SELECT
        t2.id,
        CONCAT(t2.transaction_type, ' FROM ', a_from.account_id, ' TO ', a_to.account_id) AS new_description
    FROM transactions t2
    JOIN account a_to ON a_to.id = t2.account_id
    JOIN account a_from ON a_from.id = t2.from_account_id
    WHERE t2.transaction_type IN ('PAYMENT', 'CLAIM', 'CLEAR', 'CONTRA')
      AND UPPER(t2.description) LIKE CONCAT(UPPER(t2.transaction_type), ' FROM %')
      AND UPPER(t2.description) NOT LIKE '% TO %'
) src ON src.id = t.id
SET t.description = src.new_description;
