-- One-off DATA CORRECTION (not raw migration): backfills the blank `id_product` column on
-- `data_capture_line` using the same fallback legacy PHP's own history_api.php read side always used
-- (id_product_sub for SUB rows, id_product_main otherwise) -- see MIGRATION_LOG.md §25 for the full
-- writeup.
--
-- Root cause (confirmed, not guessed): `id_product` is empty in the LEGACY source data itself
-- (data_capture_details.id_product), not something the migration script (§5,
-- migrate_data_datacapture_from_legacy.sql) got wrong -- it copied the column 1:1. `id_product_main`/
-- `id_product_sub` were always populated in legacy, `id_product` just wasn't consistently written by
-- the old system. Confirmed current Spring Boot's own submit path
-- (DataCaptureSummaryServiceImpl.java:482-484) makes `id_product` a required field for every new line
-- (GAME and BANK alike, no category-specific gap) -- so this is purely a historical data quality issue
-- inherited from legacy, not something the current app can reproduce going forward.
--
-- Scope: 75,234 total data_capture_line rows, 59,615 (~79%) have a blank id_product. Verified before
-- writing this: 0 rows lack BOTH id_product_main and id_product_sub, so the fallback below has no gaps
-- to fall through -- every blank row gets backfilled.
--
-- Idempotent: guarded by (id_product IS NULL OR id_product = ''), so a row already backfilled no
-- longer matches and re-running is a no-op. Safe to re-run.
--
-- Usage:
--   mysql -u root count_real < backend/src/main/resources/SqlEtcForMigrate/fix_data_capture_line_id_product_backfill.sql

UPDATE data_capture_line
SET id_product = CASE
    WHEN product_type = 'SUB' AND id_product_sub IS NOT NULL AND id_product_sub <> ''
        THEN id_product_sub
    ELSE id_product_main
END
WHERE id_product IS NULL OR id_product = '';
