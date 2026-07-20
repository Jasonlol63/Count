-- Legacy account.role UPLINE → SUPPLIER
UPDATE `account`
SET `role` = 'SUPPLIER'
WHERE UPPER(TRIM(`role`)) = 'UPLINE';
