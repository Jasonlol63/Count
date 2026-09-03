-- One-off DATA CORRECTION (not raw migration): swaps account_id <-> from_account_id on the 364
-- legacy-migrated RATE leg1/leg2 rows so Payment History's Cr/Dr sign matches the legacy c168.net
-- (count168.com) production display -- full writeup to be added to MIGRATION_LOG.md alongside this
-- script.
--
-- Background: `TransactionHistoryMapper.xml` / `findDomainPaymentHistoryLines` computes Cr/Dr with
-- the plain formula (no RATE-specific reversal):
--     WHEN account_id = viewed account THEN -amount
--     ELSE amount
-- This is correct for RATE rows created by the CURRENT app (`TransactionSubmitServiceImpl.submitRate`
-- inserts leg1/leg2 with account_id = leg.toAccountId(), from_account_id = leg.fromAccountId() --
-- verified against a live example, id 151363/151364, A1/A2, matches the app's own Payment History
-- display). It is WRONG for RATE rows migrated from the legacy PHP database: `account_id`/
-- `from_account_id` were copied verbatim from legacy `transactions` (migrate_data_transactions_from_
-- legacy.sql, no transformation -- verified byte-for-byte identical against
-- c168_net_legacy_20260827.transactions for a sample), and legacy's own display logic
-- (MIGRATION_LOG.md §15, history_api.php lines 2199-2207) applied a REVERSED formula for RATE
-- specifically (account_id column -> +amount, from_account_id column -> -amount) -- a rule the
-- current app's mapper no longer implements anywhere (superseded some months ago by the "RATE 功能
-- 大优化" that switched to the insert-time convention above; nothing was added to keep legacy-shaped
-- rows correct under the new formula).
--
-- Proven algebraically (and confirmed against two real examples, tenant 6 / account XE, rate groups
-- RATE_1788235476_4407 and RATE_1788235553_2786): swapping account_id <-> from_account_id on a
-- legacy-migrated leg row, THEN evaluating the CURRENT (unreversed) formula on the swapped columns,
-- produces exactly the same result as evaluating legacy's OLD (reversed) formula directly on the
-- original (unswapped) columns. So this swap is sufficient to make Payment History match
-- count168.com again -- no mapper/SQL formula change needed, and `amount` is never touched.
--
-- Scope, verified by read-only queries before writing this script:
--   - Legacy-migrated RATE groups use a `RATE_<epoch>_<rand>` rate_group_id; groups created by the
--     current app use `RG-<epoch>-<rand>`. The two prefixes never overlap (182 distinct `RATE_%`
--     groups vs 1 `RG-%` group, full-table check).
--   - All 182 `RATE_%` groups together contribute exactly 364 `transactions` rows, and every one of
--     those 364 rows is registered as either `leg1_transaction_id` or `leg2_transaction_id` in
--     `transactions_rate` (182 x 2 = 364, exact match) -- so filtering by rate_group_id LIKE 'RATE\_%'
--     AND membership in transactions_rate's leg columns cleanly selects ONLY true leg1/leg2 transfer
--     rows. It does not touch the separate legacy Middle-Man fee rows (those have rate_group_id = NULL
--     and were already handled by fix_rate_charge_self_referencing_from_account_v2.sql /
--     legacyRateMiddlemanFeeCredit-Debit -- see MIGRATION_LOG.md §16/§17).
--   - Breakdown by tenant (5 tenants, 182 groups / 364 rows total):
--       tenant_id=2  (code '95')  : 93 groups / 186 rows
--       tenant_id=5  (code 'AG')  : 64 groups / 128 rows
--       tenant_id=6  (code 'CX')  : 16 groups /  32 rows
--       tenant_id=3  (code 'RS')  :  8 groups /  16 rows
--       tenant_id=22 (code 'BK1') :  1 group  /   2 rows
--
-- ⚠️ Same portability note as fix_domain_fee_commission_account_direction_swap.sql: a naive
-- single-table `SET account_id = from_account_id, from_account_id = account_id` does NOT reliably
-- swap two columns on this MariaDB server (the second assignment reads the ALREADY-UPDATED value of
-- the first, collapsing both columns to the same value instead of swapping). The UPDATE below joins
-- against a snapshot subquery instead, so the SET clause reads from a separate, unaffected result set.
-- Do not revert to the inline two-column SET form.
--
-- NOT idempotent by construction (a second run would swap the columns back to the wrong state) --
-- this is a one-off, run-once script, same as fix_rate_charge_self_referencing_from_account_v2.sql.
-- The WHERE clause is scoped by `rate_group_id LIKE 'RATE\_%'` plus transactions_rate leg membership,
-- which stays true before AND after the swap (the swap never touches rate_group_id), so re-running
-- this script IS NOT SAFE -- run it exactly once, and use the verification query at the bottom to
-- confirm before/after row counts rather than re-running the UPDATE to "check".
--
-- Usage:
--   mysql -u root count_real < backend/src/main/resources/SqlEtcForMigrate/fix_migrated_rate_leg_account_direction_swap.sql

UPDATE transactions t
JOIN (
    SELECT id, account_id, from_account_id
    FROM transactions
    WHERE transaction_type = 'RATE'
      AND rate_group_id LIKE 'RATE\_%'
      AND id IN (
          SELECT leg1_transaction_id FROM transactions_rate
          UNION
          SELECT leg2_transaction_id FROM transactions_rate
      )
) src ON src.id = t.id
SET t.account_id = src.from_account_id,
    t.from_account_id = src.account_id;

-- Verification (run after the UPDATE):
--   Expect 182 groups / 364 rows still selected (swap doesn't change the row count in scope,
--   only account_id/from_account_id within each row).
-- SELECT COUNT(DISTINCT rate_group_id) AS groups, COUNT(*) AS rows
-- FROM transactions
-- WHERE transaction_type = 'RATE'
--   AND rate_group_id LIKE 'RATE\_%'
--   AND id IN (
--       SELECT leg1_transaction_id FROM transactions_rate
--       UNION
--       SELECT leg2_transaction_id FROM transactions_rate
--   );
--
--   Spot-check against the two examples verified by hand (tenant 6, account XE = id 4430):
--   expect id 19273 (account_id/from_account_id swapped from 4430/4594 to 4594/4430) and
--   id 19275 (swapped from 4430/4594 to 4594/4430) -- viewing account 4430 (XE) should now compute
--   +5999.99 and +5000.01 via the current (unreversed) Cr/Dr formula, matching count168.com.
-- SELECT id, account_id, from_account_id, amount FROM transactions WHERE id IN (19272,19273,19274,19275);
