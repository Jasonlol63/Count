-- Allow multiple announcements per company (C168).
-- Run once on existing DBs that were created from older testcount_schema.sql.
-- Error "Can't DROP ... check that column/key exists" means the unique key was already removed.

ALTER TABLE `announcements`
  DROP INDEX `uk_announcements_company_code`;

ALTER TABLE `announcements`
  ADD KEY `idx_announcements_company_code` (`company_code`);
