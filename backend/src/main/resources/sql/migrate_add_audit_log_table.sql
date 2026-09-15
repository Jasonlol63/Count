-- Adds `audit_log`: records every CRUD write operation across the app for the IT
-- audit-log console. One row per operation (not per field) -- before/after snapshots
-- are stored as plain TEXT (a JSON-*formatted* string), not MySQL's native JSON column
-- type, to avoid JSON-type storage/index quirks while keeping row count proportional
-- to operations, not to fields touched per operation (see docs/it-role-audit-log.md
-- for the design discussion, including why a per-field row table was rejected).
--
-- Design notes:
-- - before_data/after_data field names match the DATABASE column names of source_table
--   (not Java entity field names), so the snapshot can be used directly for manual
--   DB recovery without guessing a field-name mapping.
-- - restorable/restored/restored_by/restored_at/related_log_id support the Restore
--   flow (Payment/BankProcess/CaptureTransaction deletes, restored from
--   `transactions_deleted`); most modules (e.g. ACCOUNT) just log with restorable=0.
-- - tenant_id/tenant_code are nullable because IT operators aren't scoped to one
--   tenant the same fixed way Admin/Owner sessions are.
-- - Global (non-tenant-owned) table, like exchange_rate/platform_settings -- a single
--   audit_log row can belong to any tenant, and the IT console queries across all of
--   them, so it lives in schema.sql's "Global (non-tenant-scoped) tables" section.
--
-- Safe to re-run: CREATE TABLE IF NOT EXISTS.
-- Example: mysql -u root testcount < backend/src/main/resources/sql/migrate_add_audit_log_table.sql

CREATE TABLE IF NOT EXISTS `audit_log` (
    `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `operator_id`    VARCHAR(50)   NULL     COMMENT 'Admin/Owner id as string; NULL for IT operators (no DB row)',
    `operator_name`  VARCHAR(100)  NOT NULL,
    `operator_role`  VARCHAR(30)   NOT NULL COMMENT 'e.g. ADMIN, MANAGER, IT',
    `tenant_id`      INT           NULL,
    `tenant_code`    VARCHAR(20)   NULL,
    `module`         VARCHAR(50)   NOT NULL COMMENT 'e.g. PAYMENT_MAINTENANCE, ACCOUNT',
    `action`         ENUM('CREATE','UPDATE','DELETE','RESTORE') NOT NULL,
    `entity_id`      VARCHAR(50)   NOT NULL COMMENT 'Business-facing id, e.g. PMT-88213',
    `source_table`   VARCHAR(50)   NOT NULL COMMENT 'Real DB table name, for manual recovery reference',
    `summary`        VARCHAR(255)  NULL,
    `before_data`    TEXT          NULL     COMMENT 'JSON-formatted text, NOT the JSON column type; DB column names',
    `after_data`     TEXT          NULL,
    `restorable`     TINYINT(1)    NOT NULL DEFAULT 0,
    `restored`       TINYINT(1)    NOT NULL DEFAULT 0,
    `restored_by`    VARCHAR(100)  NULL,
    `restored_at`    TIMESTAMP     NULL,
    `related_log_id` BIGINT UNSIGNED NULL   COMMENT 'RESTORE rows point back at the DELETE row they restored',
    `created_at`     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_tenant_time` (`tenant_id`, `created_at`),
    KEY `idx_module_action` (`module`, `action`),
    KEY `idx_related_log` (`related_log_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='CRUD audit trail for the IT console -- one row per write operation';
