-- Add Platform Fee + Rate-Mul raw expression to transactions_rate (match sql/schema.sql).
-- Safe to re-run only if the columns do not already exist (or drop them first).
--
-- middleman_amount semantics change (Service Fee): now face value in currency_to (leg2),
-- no FX conversion (was: currency_from (leg1), converted x exchange_rate). Existing rows are
-- NOT reinterpreted by this migration -- old rows were stored under the old semantics.

ALTER TABLE `transactions_rate`
    MODIFY COLUMN `middleman_amount` DECIMAL(25, 8) DEFAULT NULL
        COMMENT 'Service Fee face value in currency_to (leg2); no FX conversion',
    ADD COLUMN `middleman_rate_expression` VARCHAR(32) NULL DEFAULT NULL
        COMMENT 'Raw Rate-Mul input, e.g. /1.55 (divide) or 2.93 (multiply/new-rate); drives mode parsing'
        AFTER `middleman_rate`,
    ADD COLUMN `platform_fee_amount` DECIMAL(25, 8) NULL DEFAULT NULL
        COMMENT 'Platform Fee face value in currency_to (leg2); reduces middleman Win/Loss (Fee - PT), no separate ledger row'
        AFTER `middleman_amount`;
