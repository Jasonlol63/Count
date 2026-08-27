-- Account-level sidebar permission override (adds/removes on top of the account's role default).
--
-- `user.permission_mode`:
--   ROLE_DEFAULT (default) — sidebar permissions come entirely from user_role_permission (unchanged
--                             behavior, zero extra query).
--   CUSTOM                 — sidebar permissions come entirely from user_permission_override for this
--                             user_id (a complete explicit list, not a diff — can be a superset or
--                             subset of the role's default). Never both sources at once.
--
-- Mirrors the existing account_acl_mode / process_acl_mode (ALL/CUSTOM/NONE) pattern already used
-- on user_tenant_access — same "one concept, one table" convention, not a new paradigm.
--
-- Safe to re-run (idempotent).
-- Example: mysql -u root testcount < backend/src/main/resources/sql/migrate_add_user_permission_override.sql

USE testcount;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND COLUMN_NAME = 'permission_mode'
);
SET @sql := IF(
    @col_exists = 0,
    'ALTER TABLE `user` ADD COLUMN `permission_mode` ENUM(''ROLE_DEFAULT'',''CUSTOM'') NOT NULL DEFAULT ''ROLE_DEFAULT'' COMMENT ''ROLE_DEFAULT=按角色默认权限；CUSTOM=按 user_permission_override 的完整清单'' AFTER `read_only`',
    'SELECT ''user.permission_mode already exists, skipping'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS `user_permission_override` (
    `user_id`       INT UNSIGNED NOT NULL,
    `permission_id` SMALLINT UNSIGNED NOT NULL,
    `created_at`    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`user_id`, `permission_id`),
    KEY `idx_upo_permission_id` (`permission_id`),
    CONSTRAINT `fk_upo_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_upo_permission` FOREIGN KEY (`permission_id`) REFERENCES `permission` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='CUSTOM 模式账号的完整侧边栏权限清单，与 user_role_permission（角色默认）互斥使用，按 user_id 精确隔离，不影响同角色其他账号';
