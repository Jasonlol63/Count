-- Drop unused legacy RECEIVE from transactions.transaction_type.
-- Only run after confirming no rows still use transaction_type = 'RECEIVE'.
ALTER TABLE `transactions`
    MODIFY COLUMN `transaction_type`
    ENUM('WIN', 'LOSE', 'PAYMENT', 'CONTRA', 'CLAIM', 'RATE', 'CLEAR', 'ADJUSTMENT', 'PROFIT')
    NOT NULL;

ALTER TABLE `transactions_deleted`
    MODIFY COLUMN `transaction_type`
    ENUM('WIN', 'LOSE', 'PAYMENT', 'CONTRA', 'CLAIM', 'RATE', 'CLEAR', 'ADJUSTMENT', 'PROFIT')
    NOT NULL;
