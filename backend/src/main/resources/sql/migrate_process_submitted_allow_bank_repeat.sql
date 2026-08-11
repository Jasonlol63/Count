-- process_submitted: GAME and BANK Submit both now write a row (previously BANK never inserted
-- one, see DataCaptureSummaryServiceImpl.submit() §3). GAME same-process/same-day dedup is
-- enforced purely at the service layer (existsProcessSubmitted), not by a DB constraint, because
-- BANK must be allowed to submit the same process on the same day multiple times (shown as
-- separate rows in Submitted Processes, distinguished by created_at). Drop the UNIQUE key that
-- would otherwise reject BANK's repeat inserts and replace it with a plain index (keeps
-- existsProcessSubmitted's lookup fast).

SET @has_unique := (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'process_submitted'
    AND INDEX_NAME = 'uk_submitted_tenant_process_date'
);
SET @sql := IF(@has_unique > 0,
  'ALTER TABLE `process_submitted` DROP INDEX `uk_submitted_tenant_process_date`',
  'SELECT ''uk_submitted_tenant_process_date already dropped'' AS info');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_plain := (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'process_submitted'
    AND INDEX_NAME = 'idx_sp_tenant_process_date'
);
SET @sql := IF(@has_plain = 0,
  'ALTER TABLE `process_submitted` ADD KEY `idx_sp_tenant_process_date` (`tenant_id`, `process_id`, `capture_date`)',
  'SELECT ''idx_sp_tenant_process_date already exists'' AS info');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
