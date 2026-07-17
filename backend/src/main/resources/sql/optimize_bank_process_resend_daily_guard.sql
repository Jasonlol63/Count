-- Optimize bank_process_resend_daily_guard indexes for lock-check + list-today prune.
-- Safe to re-run only if the old unique/index names still exist.

ALTER TABLE `bank_process_resend_daily_guard`
    DROP INDEX `idx_bprdg_today`,
    DROP INDEX `uk_bprdg`,
    ADD UNIQUE KEY `uk_bprdg` (`tenant_id`, `bank_process_id`, `guard_date`, `resend_day_start`);
