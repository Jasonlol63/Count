-- Adds `system_maintenance_mode`: a singleton (single-row) global switch for the IT
-- console's "kick everyone" maintenance mode -- when enabled, every non-IT session is
-- rejected on its next request (see JwtAuthTokenFilter), with no per-tenant scoping.
--
-- Design notes:
-- - Global, not tenant-scoped: same singleton pattern as `platform_settings` (id = 1,
--   exactly one row, app never inserts additional rows) -- there is no tenant_id column
--   because the IT operator confirmed this switch is unconditional and system-wide, not
--   scoped to a specific company/group.
-- - No enabled_by/enabled_at/disabled_by/disabled_at audit columns: who flipped the switch
--   and when is already captured by the `@Audited` AOP into `audit_log` (module =
--   SYSTEM_MAINTENANCE) -- duplicating that history on this row would just be two sources
--   of truth for the same fact.
-- - `enabled` is a real TINYINT(1), not a stringly-typed key-value settings row, so the
--   hot-path read in JwtAuthTokenFilter (via a Redis-cached mirror of this value) needs no
--   parsing/casting.
--
-- Safe to re-run: CREATE TABLE IF NOT EXISTS + INSERT IGNORE.
-- Example: mysql -u root testcount < backend/src/main/resources/sql/migrate_add_system_maintenance_mode_table.sql

CREATE TABLE IF NOT EXISTS `system_maintenance_mode` (
    `id`         TINYINT UNSIGNED NOT NULL COMMENT 'Always 1 -- singleton row',
    `enabled`    TINYINT(1) NOT NULL DEFAULT 0 COMMENT '1 = maintenance mode ON, all non-IT sessions rejected',
    `updated_at` TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Singleton row (id=1) global switch for IT-only system-wide maintenance/kick mode';

INSERT IGNORE INTO `system_maintenance_mode` (`id`, `enabled`) VALUES (1, 0);
