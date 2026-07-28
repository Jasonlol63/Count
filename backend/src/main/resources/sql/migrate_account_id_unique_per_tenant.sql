-- account.account_id uniqueness: global → per tenant (via account_tenant_access)
-- Same login code may exist under different companies; same company still rejects duplicates (app check).

ALTER TABLE `account`
    DROP INDEX `uk_account_account_id`,
    ADD KEY `idx_account_account_id` (`account_id`);
