-- One-off cleanup (not the main migration): merges 7 genuinely-duplicate `process` rows found after
-- the Process domain migration -- these are cases where legacy data had the SAME (company_id,
-- process_id, description_id) more than once (a real bug/re-creation in the old system), as opposed
-- to the far more common case of one code deliberately split across several DIFFERENT descriptions
-- (kept as separate rows -- see migrate_data_process_from_legacy.sql).
--
-- Confirmed by checking legacy `data_captures` history for each row in the 6 affected groups: every
-- group is a real, continuous business timeline split across 2-3 rows (e.g. EC23: one row used
-- weekly Apr-Aug16 with 11 real submissions, a second row picked up the very next week with 1 more).
-- `status` was NOT a reliable signal of which row "wins" -- e.g. AB33888's currently ACTIVE row has
-- ZERO captures while a 'waiting'-status sibling holds the one real submission. Survivor per group
-- was chosen by whichever row has the MOST legacy `data_captures` rows (tie -> earliest id).
--
-- Safety: `process_submitted.process_id` and `process_day.process_id` both reference `process.id`;
-- process_submitted is ON DELETE CASCADE, so deleting a loser row without first moving its
-- process_submitted/process_day rows to the survivor would silently destroy that submission history.
-- This script moves both BEFORE deleting. `process_description_link` needs no manual handling --
-- each loser's link is to the SAME description the survivor already links (that's the definition of
-- these being true duplicates), so it's simply removed by the CASCADE, leaving the survivor's own
-- (identical) link intact.
--
-- `process_duplicate_merge_map` is created as a PERSISTENT table (not a temp/session table) because
-- the Data Capture domain (data_captures / data_capture_details -- where the actual amounts live)
-- has not been migrated yet. Its migration script MUST join through this table to redirect the
-- 7 old process ids below to their surviving process id, or 2+ historical submissions (already
-- verified to exist: 4538's and 4700's) will attribute money to a process row that no longer exists.
--
-- Idempotent: safe to re-run (checks table/row existence before acting).
-- Usage: mysql -u root count_real < backend/src/main/resources/SqlEtcForMigrate/fix_process_true_duplicates.sql

CREATE TABLE IF NOT EXISTS process_duplicate_merge_map (
    old_process_id       INT UNSIGNED NOT NULL PRIMARY KEY,
    canonical_process_id INT UNSIGNED NOT NULL,
    reason                VARCHAR(255) NOT NULL,
    merged_at             TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT IGNORE INTO process_duplicate_merge_map (old_process_id, canonical_process_id, reason) VALUES
    (4700, 4138, 'EC23 (tenant 95) desc "OBET33 LC": true duplicate, merged into the row with 11 legacy data_captures vs 1'),
    (4538, 4267, 'INFINITY688US-2 (tenant AG) desc "INFINITY688 API USD": true duplicate, 1 capture each, kept earliest id'),
    (4687, 4689, 'AB33888 (tenant 95) desc "XE88 LC": true duplicate (3-way), merged into the only row with a legacy capture'),
    (4701, 4689, 'AB33888 (tenant 95) desc "XE88 LC": true duplicate (3-way), merged into the only row with a legacy capture'),
    (4175, 4176, 'XE8877003 (tenant 95) desc "XE88 LC": true duplicate, merged into the row with 3 legacy data_captures vs 0'),
    (4417, 4419, 'AP7FT003 (tenant RS) desc "WCC分图": true duplicate, merged into the row with 6 legacy data_captures vs 0'),
    (4590, 4591, 'REDIRECT2UMYR (tenant RS) desc "API BWG": true duplicate, merged into the row with 2 legacy data_captures vs 0');

-- Move process_day first (dedup via INSERT IGNORE -- uk_process_day (process_id, day_of_week)).
INSERT IGNORE INTO process_day (process_id, day_of_week)
SELECT m.canonical_process_id, pd.day_of_week
FROM process_day pd
JOIN process_duplicate_merge_map m ON m.old_process_id = pd.process_id;

-- Move process_submitted (no unique constraint to dedup against -- these are individual submission
-- events, both rows' history must survive as-is, just repointed).
UPDATE process_submitted ps
JOIN process_duplicate_merge_map m ON m.old_process_id = ps.process_id
SET ps.process_id = m.canonical_process_id;

-- Now safe to delete the loser rows: their process_day rows were already carried over (any
-- remaining ones under the old id are cascade-removed here, which is correct -- they're now
-- duplicated on the canonical row); process_submitted rows were already moved off; their
-- process_description_link row is a duplicate of the canonical row's own link, correctly removed.
DELETE FROM process WHERE id IN (SELECT old_process_id FROM process_duplicate_merge_map);
