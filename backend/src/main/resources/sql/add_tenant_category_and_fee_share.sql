-- Add tenant.category_code and tenant.fee_share_allocate (run once on existing testcount DBs).
-- Skip if the table was created from an updated testcount_schema.sql that already includes these columns.

USE testcount;

ALTER TABLE `tenant`
  ADD COLUMN `category_code` JSON DEFAULT NULL COMMENT 'Business modules e.g. ["GAME","BANK"]' AFTER `expiration_date`,
  ADD COLUMN `fee_share_allocate` JSON DEFAULT NULL COMMENT 'Sales/CS/IT/Profit share % by account' AFTER `category_code`;
