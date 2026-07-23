-- Migrate existing Spring DB to optimized RATE tables (match sql/schema.sql).
-- Does NOT create legacy transactions_rate_details / transaction_entry.
-- Safe to re-run only if rate_group_id / transactions_rate do not already exist (or drop first).

-- 1) transactions.rate_group_id
ALTER TABLE `transactions`
    ADD COLUMN `rate_group_id` VARCHAR(50) NULL DEFAULT NULL
        COMMENT 'RATE only: shared by leg1+leg2; NULL for other types'
        AFTER `bank_process_posted_id`,
    ADD KEY `idx_txn_tenant_rate_group` (`tenant_id`, `rate_group_id`);

-- 2) Slim transactions_rate (drop wide legacy table if present)
DROP TABLE IF EXISTS `transactions_rate`;

CREATE TABLE `transactions_rate` (
    `id`                   INT UNSIGNED NOT NULL AUTO_INCREMENT,
    `tenant_id`            INT UNSIGNED NOT NULL COMMENT 'FK tenant.id',
    `rate_group_id`        VARCHAR(50)  NOT NULL COMMENT 'Shared with transactions.rate_group_id',

    `leg1_transaction_id`  INT UNSIGNED NOT NULL COMMENT 'First currency leg FK transactions.id (e.g. MYR)',
    `leg2_transaction_id`  INT UNSIGNED NOT NULL COMMENT 'Second currency leg FK transactions.id (e.g. CNY)',

    `exchange_rate`        DECIMAL(18, 8) NOT NULL COMMENT 'Effective multiplier (amount_to/amount_from); /1.7 stored as 1/1.7',
    `rate_expression`      VARCHAR(64) DEFAULT NULL COMMENT 'UI raw e.g. 1.7 or /1.7',
    `currency_from_id`     INT UNSIGNED NOT NULL COMMENT 'Leg1 currency FK currency.id',
    `amount_from`          DECIMAL(25, 8) NOT NULL COMMENT 'Leg1 amount > 0',
    `currency_to_id`       INT UNSIGNED NOT NULL COMMENT 'Leg2 currency FK currency.id',
    `amount_to`            DECIMAL(25, 8) NOT NULL COMMENT 'Leg2 amount > 0',

    `middleman_account_id` INT UNSIGNED DEFAULT NULL COMMENT 'FK account.id; Middle-Man fee account',
    `middleman_rate`       DECIMAL(18, 8) DEFAULT NULL COMMENT 'Middle-Man multiplier (paired with account)',
    `middleman_amount`     DECIMAL(25, 8) DEFAULT NULL COMMENT 'Fee input in currency_from (leg1); converted × exchange_rate for fee leg',

    `created_at`           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_rate_group` (`tenant_id`, `rate_group_id`),
    KEY `idx_rate_leg1` (`leg1_transaction_id`),
    KEY `idx_rate_leg2` (`leg2_transaction_id`),

    CONSTRAINT `fk_rate_tenant`
        FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_rate_leg1_txn`
        FOREIGN KEY (`leg1_transaction_id`) REFERENCES `transactions` (`id`),
    CONSTRAINT `fk_rate_leg2_txn`
        FOREIGN KEY (`leg2_transaction_id`) REFERENCES `transactions` (`id`),
    CONSTRAINT `fk_rate_ccy_from`
        FOREIGN KEY (`currency_from_id`) REFERENCES `currency` (`id`),
    CONSTRAINT `fk_rate_ccy_to`
        FOREIGN KEY (`currency_to_id`) REFERENCES `currency` (`id`),
    CONSTRAINT `fk_rate_middleman_account`
        FOREIGN KEY (`middleman_account_id`) REFERENCES `account` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='RATE group header: exchange_rate + leg txn links; Cr/Dr from transactions only';
