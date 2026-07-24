-- Add PROFIT to transactions.transaction_type (manual profit Win/Loss, From+To).
ALTER TABLE `transactions`
    MODIFY COLUMN `transaction_type`
    ENUM('WIN', 'LOSE', 'PAYMENT', 'CONTRA', 'CLAIM', 'RATE', 'CLEAR', 'ADJUSTMENT', 'PROFIT')
    NOT NULL;
