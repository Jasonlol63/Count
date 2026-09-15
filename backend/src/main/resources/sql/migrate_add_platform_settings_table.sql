-- Adds `platform_settings`: a singleton (single-row) table for global, platform-level
-- config values that admins edit at runtime -- starting with the Telegram support link
-- shown as a floating button on the (unauthenticated) login page.
--
-- Design notes:
-- - Global, not tenant-scoped: unlike `announcements`/`maintenance_marquee` (which carry
--   company_code = 'C168'), this is not tied to any tenant row -- it's platform-wide config,
--   so it deliberately has no company_code/tenant_id column.
-- - Singleton pattern: exactly one row, id = 1, seeded by this migration. Reads/writes always
--   target id = 1; the app never inserts additional rows.
-- - Room to grow: future global toggles/links can be added as new nullable columns on this
--   same row rather than new tables, matching how this migration itself was scoped down from
--   a generic JSON "settings" blob (this schema's convention is normalized columns, not JSON --
--   see exchange_rate, tenant_fee_share_allocation, account_tenant_access for precedent).
--
-- Safe to re-run: CREATE TABLE IF NOT EXISTS + INSERT IGNORE.
-- Example: mysql -u root count_real < backend/src/main/resources/sql/migrate_add_platform_settings_table.sql

CREATE TABLE IF NOT EXISTS `platform_settings` (
    `id`                    TINYINT UNSIGNED NOT NULL COMMENT 'Always 1 -- singleton row',
    `telegram_support_link` VARCHAR(500) NULL COMMENT 'Telegram support URL for the login-page button; NULL/empty = button hidden',
    `updated_by`            VARCHAR(50)  NULL COMMENT 'Last editor login_id (admin=user.login_id; owner=owner_code)',
    `updated_by_type`       ENUM('USER', 'OWNER') NULL COMMENT 'Last editor identity table',
    `updated_at`            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Singleton row (id=1) for global platform-level settings, e.g. Telegram support link';

INSERT IGNORE INTO `platform_settings` (`id`) VALUES (1);
