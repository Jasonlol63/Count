-- One-off DATA CORRECTION: fixes the `bank_process_posted_id` cross-contamination bug (§10 in
-- SYNC_20260905_VERIFICATION.md) for tenant 18 ("23" company), for the 2026-09-05 delta round.
--
-- Root cause (§10.1): migrate_delta_transactions_and_accounting_due_20260905(_part2).sql step 6 used
--   bap.id = COALESCE((SELECT new_id FROM _new_bap_map WHERE legacy_id = lp.id), lp.id)
-- which wrongly reused an OLD numeric `process_accounting_posted.id` as if it were the same id in
-- `bank_process_accounting_posted`, whenever that legacy posted-record id was not itself new this
-- round. Tenant 18's legacy heavily re-triggers "resend consolidated" postings, so this false
-- assumption fired constantly.
--
-- Method (precise, not a business-key guess): the 2026-09-05 round's legacy_id<->count_real_id
-- mapping is deterministically reconstructed exactly as the original script built it (same ORDER BY,
-- same base id 151377 -- independently verified against known-good examples: legacy 20305/20306/20307
-- -> count_real 151468/151469/151470, all confirmed by matching content). For every resulting
-- tenant-18 row, its OWN legacy `source_bank_process_id`/`source_bank_process_period_type` (read
-- directly from the legacy row, still active in the 2026-09-05 dump) is resolved to the correct
-- `bank_process_accounting_posted` row via (tenant, bank_process, posted_date, period_type) -- business
-- key, not id reuse. 70 of the round's 100 tenant-18 rows turn out to need a fix (9 were already
-- correct, 21 have no bank-process link at all and are untouched).
--
-- This script only UPDATEs bank_process_posted_id. Descriptions (previously overwritten with the
-- WRONG company's text by BankProcessDescriptionBackfillTool) must be regenerated afterwards by
-- re-running that tool with --apply (it is idempotent/scoped by current bank_process_posted_id, so it
-- will pick up these 70 rows' corrected link automatically).
--
-- Usage:
--   mysql -u root count_real < backend/src/main/resources/SqlEtcForMigrate/fix_tenant18_bank_process_posted_id_relink_20260905round.sql

START TRANSACTION;

CREATE TEMPORARY TABLE _new_txn_map (
    legacy_id INT NOT NULL PRIMARY KEY,
    new_id    INT NOT NULL UNIQUE
);
SET @base_txn := 151377;
INSERT INTO _new_txn_map (legacy_id, new_id)
SELECT x.legacy_id, @base_txn + x.rn
FROM (
    SELECT t.id AS legacy_id, ROW_NUMBER() OVER (ORDER BY t.id) AS rn
    FROM c168_net_legacy_20260905.transactions t
    JOIN c168_net_legacy_20260905.company c ON c.id = t.company_id
    JOIN tenant ten ON ten.tenant_type = 'COMPANY' AND ten.code = c.company_id
    WHERE t.id NOT IN (SELECT id FROM c168_net_legacy_20260903.transactions)
) x;

-- Sanity check before touching anything: must reproduce the known-good contiguous block.
SELECT COUNT(*) AS should_be_221, MIN(new_id) AS should_be_151378, MAX(new_id) AS should_be_151598
FROM _new_txn_map;

CREATE TEMPORARY TABLE _resolve AS
SELECT
    nm.new_id AS txn_id,
    correct.id AS correct_bap_id,
    t.bank_process_posted_id AS current_bap_id
FROM _new_txn_map nm
JOIN count_real.transactions t ON t.id = nm.new_id AND t.tenant_id = 18
JOIN c168_net_legacy_20260905.transactions la ON la.id = nm.legacy_id
JOIN bank_process_accounting_posted correct
    ON correct.tenant_id = 18
   AND correct.bank_process_id = la.source_bank_process_id
   AND correct.posted_date = t.transaction_date
   AND correct.period_type = 'RESEND_CONSOLIDATED'
WHERE la.source_bank_process_id IS NOT NULL
  AND (t.bank_process_posted_id IS NULL OR t.bank_process_posted_id <> correct.id);

-- Sanity check: must be exactly 70 rows before applying.
SELECT COUNT(*) AS should_be_70 FROM _resolve;

UPDATE count_real.transactions t
JOIN _resolve r ON r.txn_id = t.id
SET t.bank_process_posted_id = r.correct_bap_id;

SELECT ROW_COUNT() AS rows_updated;

DROP TEMPORARY TABLE _resolve;
DROP TEMPORARY TABLE _new_txn_map;

COMMIT;
