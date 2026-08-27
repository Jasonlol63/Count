-- `user.read_only` used to default to 1 (read-only) for every new admin/staff account. That was
-- harmless while nothing enforced it, but now that AccessControlUtils.requireWritable blocks all
-- writes for read_only=1 accounts, any role without a frontend toggle to turn it back off
-- (everything except Partnership/Audit) would be permanently stuck read-only.
--
-- New default: 0 (writable). Partnership/Audit are unaffected — the frontend always sends an
-- explicit `readOnly` value for those two roles on create/update, so they never relied on this
-- column default in the first place.
--
-- Also backfills existing non-Partnership/Audit accounts from 1 -> 0, since for those roles the
-- 1 was never a deliberate choice by anyone — just the old default nobody could change.
--
-- Safe to re-run (idempotent).
-- Example: mysql -u root testcount < backend/src/main/resources/sql/migrate_admin_read_only_default_false.sql

USE testcount;

ALTER TABLE `user` ALTER COLUMN `read_only` SET DEFAULT 0;

UPDATE `user` u
INNER JOIN `user_role` ur ON ur.id = u.role_id
SET u.read_only = 0
WHERE u.read_only = 1
  AND ur.code NOT IN ('PARTNERSHIP', 'AUDIT');
