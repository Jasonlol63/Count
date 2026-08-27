-- Role hierarchy + Admin sidebar permission correction.
--
-- Background: `user_role.hierarchy_level` currently ranks PARTNERSHIP last (8), while the
-- frontend's own ROLE_HIERARCHY ranks it 2nd (right below OWNER). The two disagreed with
-- each other and neither was actually enforced anywhere. This migration makes the DB the
-- single source of truth, matching the agreed business rule:
--   OWNER(1) > PARTNERSHIP(2) > ADMIN(3) > MANAGER(4) > SUPERVISOR(5) > ACCOUNTANT/AUDIT/CUSTOMER_SERVICE(6-8)
--
-- Also removes CUSTOMER_SERVICE's ADMIN (staff list) sidebar entry — that role was never
-- supposed to see/manage the admin user list; SUPERVISOR keeps it (with a restricted CRUD
-- scope enforced at the service layer, not covered by this migration).
--
-- Safe to re-run (idempotent).
-- Example: mysql -u root testcount < backend/src/main/resources/sql/migrate_role_hierarchy_and_admin_permission_fix.sql

USE testcount;

UPDATE `user_role` SET `hierarchy_level` = 1 WHERE `code` = 'OWNER';
UPDATE `user_role` SET `hierarchy_level` = 2 WHERE `code` = 'PARTNERSHIP';
UPDATE `user_role` SET `hierarchy_level` = 3 WHERE `code` = 'ADMIN';
UPDATE `user_role` SET `hierarchy_level` = 4 WHERE `code` = 'MANAGER';
UPDATE `user_role` SET `hierarchy_level` = 5 WHERE `code` = 'SUPERVISOR';
UPDATE `user_role` SET `hierarchy_level` = 6 WHERE `code` = 'ACCOUNTANT';
UPDATE `user_role` SET `hierarchy_level` = 7 WHERE `code` = 'AUDIT';
UPDATE `user_role` SET `hierarchy_level` = 8 WHERE `code` = 'CUSTOMER_SERVICE';

DELETE `urp` FROM `user_role_permission` `urp`
    JOIN `user_role` r ON r.id = urp.role_id
    JOIN `permission` p ON p.id = urp.permission_id
WHERE r.code = 'CUSTOMER_SERVICE' AND p.code = 'ADMIN';
