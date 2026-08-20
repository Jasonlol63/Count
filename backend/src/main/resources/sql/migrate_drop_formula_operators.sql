-- Drop the redundant data_capture_formula.formula_operators column.
-- Root cause: `formula` and `formula_operators` were always meant to hold identical text
-- (every working save path wrote the same value to both — see DataCaptureSummaryServiceImpl
-- saveAsMain/saveAsSub/updateFormula before this change), but Formula Maintenance's update
-- endpoint (MaintenanceMapper.updateFormulaMaintenanceRow) only ever wrote `formula`, and the
-- calculation code read `formula_operators` preferentially — so editing a formula in
-- Formula Maintenance silently never affected the computed Processed Amount on Summary.
-- Fix: consolidate onto the single `formula` column everywhere (backend + frontend), then
-- drop the now-unused column here.
--
-- Safe to run multiple times / against a DB that never had the column.

SET @has_formula_operators := (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'data_capture_formula'
    AND COLUMN_NAME = 'formula_operators'
);

SET @sql := IF(@has_formula_operators > 0,
  'ALTER TABLE `data_capture_formula` DROP COLUMN `formula_operators`',
  'SELECT ''formula_operators already dropped'' AS info');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
