-- Adds `exchange_rate`: a lightweight, pivot-based FX rate table used by the dashboard's
-- multi-currency Amount/Original Amount/Rate breakdown (Currency & Earning tabs).
--
-- Design notes:
-- - All rates are stored against a single pivot currency (USD), one row per (currency_code,
--   rate_date). Converting any currency A -> B is done in the service layer as
--   amount * rate_to_usd(A) / rate_to_usd(B) -- no NxN matrix, no per-base-currency storage.
-- - Refreshed once a day by a scheduled job (source data itself only updates daily), so this
--   table stays small: one snapshot row per currency per day, not per hour.
-- - Stablecoins (USDT, USDC, ...) are written with rate_to_usd = 1 by the same job, no external
--   call needed for them.
-- - Global (not tenant-scoped): FX rates are not tenant-specific data.
--
-- Safe to re-run: uses CREATE TABLE IF NOT EXISTS.
-- Example: mysql -u root count_real < backend/src/main/resources/sql/migrate_add_exchange_rate_table.sql

CREATE TABLE IF NOT EXISTS `exchange_rate` (
    `id` INT UNSIGNED NOT NULL AUTO_INCREMENT,
    `currency_code` VARCHAR(10) NOT NULL COMMENT 'ISO currency code or stablecoin symbol, e.g. MYR, USD, USDT',
    `rate_to_usd` DECIMAL(18,8) NOT NULL COMMENT '1 unit of currency_code expressed in USD; USD row itself = 1',
    `rate_date` DATE NOT NULL COMMENT 'Day this snapshot represents',
    `source` VARCHAR(20) NOT NULL DEFAULT 'frankfurter' COMMENT 'frankfurter | stablecoin | manual',
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uq_exchange_rate_code_date` (`currency_code`, `rate_date`),
    KEY `idx_exchange_rate_date` (`rate_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Daily FX snapshot, all rates pivoted against USD';
