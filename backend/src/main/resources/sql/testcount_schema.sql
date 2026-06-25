-- testcount dev database — schema + C168 / JS seed (see application.yml)
CREATE DATABASE IF NOT EXISTS testcount CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE testcount;

SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS `announcements`;
DROP TABLE IF EXISTS `account_currency`;
DROP TABLE IF EXISTS `currency`;
DROP TABLE IF EXISTS `password_reset_tac_owner`;
DROP TABLE IF EXISTS `password_reset_tac`;
DROP TABLE IF EXISTS `account_tenant_access`;
DROP TABLE IF EXISTS `user_tenant_access`;
DROP TABLE IF EXISTS `account`;
DROP TABLE IF EXISTS `user`;
DROP TABLE IF EXISTS `tenant_link`;
DROP TABLE IF EXISTS `tenant_feature_module`;
DROP TABLE IF EXISTS `feature_module`;
DROP TABLE IF EXISTS `tenant`;
DROP TABLE IF EXISTS `owner`;
SET FOREIGN_KEY_CHECKS = 1;

CREATE TABLE `owner` (
  `id`                   INT UNSIGNED NOT NULL AUTO_INCREMENT,
  `owner_code`           VARCHAR(50)  NOT NULL COMMENT 'Login identifier (Admin tab)',
  `name`                 VARCHAR(150) NOT NULL,
  `email`                VARCHAR(150)          DEFAULT NULL,
  `password`             VARCHAR(255) NOT NULL COMMENT 'BCrypt hash',
  `secondary_password`   VARCHAR(255)          DEFAULT NULL COMMENT 'BCrypt hash, 6-digit PIN',
  `status`               ENUM('ACTIVE', 'INACTIVE') NOT NULL DEFAULT 'ACTIVE',
  `created_by`           VARCHAR(50)           DEFAULT NULL,
  `created_at`           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_owner_code` (`owner_code`),
  KEY `idx_owner_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Domain owner identity';

CREATE TABLE `tenant` (
  `id`                INT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_type`       ENUM('GROUP', 'COMPANY') NOT NULL,
  `code`              VARCHAR(50)  NOT NULL COMMENT 'Login / business code e.g. AP, 95, C168',
  `name`              VARCHAR(150)          DEFAULT NULL,
  `owner_id`          INT UNSIGNED          DEFAULT NULL COMMENT 'FK owner.id',
  `parent_id`         INT UNSIGNED          DEFAULT NULL COMMENT 'company → parent group tenant.id',
  `expiration_date`   DATE                  DEFAULT NULL COMMENT 'Per-tenant expiry (group or company)',
  `category_code`     JSON                  DEFAULT NULL COMMENT 'Business modules e.g. ["GAME","BANK"]',
  `fee_share_allocate` JSON                 DEFAULT NULL COMMENT 'Sales/CS/IT/Profit share % by account',
  `status`            ENUM('ACTIVE', 'INACTIVE') NOT NULL DEFAULT 'ACTIVE',
  `created_by`        VARCHAR(50)           DEFAULT NULL,
  `created_at`        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_code` (`code`),
  KEY `idx_tenant_type` (`tenant_type`),
  KEY `idx_tenant_owner_id` (`owner_id`),
  KEY `idx_tenant_parent_id` (`parent_id`),
  KEY `idx_tenant_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Group and company tenants (single ID space)';

CREATE TABLE `feature_module` (
  `id`         SMALLINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `code`       VARCHAR(50)  NOT NULL COMMENT 'Canonical module code e.g. Game, Bank',
  `name`       VARCHAR(255) NOT NULL COMMENT 'Display name',
  `sort_order` SMALLINT     NOT NULL DEFAULT 0,
  `status`     ENUM('ACTIVE', 'INACTIVE') NOT NULL DEFAULT 'ACTIVE',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_feature_module_code` (`code`),
  KEY `idx_feature_module_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Business module dictionary';

CREATE TABLE `tenant_feature_module` (
  `id`         INT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`  INT UNSIGNED NOT NULL COMMENT 'FK tenant.id',
  `module_id`  SMALLINT UNSIGNED NOT NULL COMMENT 'FK feature_module.id',
  `created_at` TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_feature_module` (`tenant_id`, `module_id`),
  KEY `idx_tfm_tenant_id` (`tenant_id`),
  KEY `idx_tfm_module_id` (`module_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Tenant feature flags via association table';

CREATE TABLE `tenant_link` (
  `id`              INT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`       INT UNSIGNED NOT NULL COMMENT 'FK tenant.id (usually a group)',
  `linked_tenant_id` INT UNSIGNED NOT NULL COMMENT 'FK tenant.id (partner group)',
  `link_type`       VARCHAR(32)  NOT NULL DEFAULT 'partner' COMMENT 'partner, aggregate, ...',
  `created_at`      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_link_pair` (`tenant_id`, `linked_tenant_id`),
  KEY `idx_tenant_link_linked` (`linked_tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Peer links between group tenants (AP+IG etc.)';

CREATE TABLE `user` (
  `id`                       INT UNSIGNED NOT NULL AUTO_INCREMENT,
  `login_id`                 VARCHAR(50)  NOT NULL COMMENT 'Login identifier (Admin tab)',
  `name`                     VARCHAR(100) NOT NULL,
  `email`                    VARCHAR(100) NOT NULL,
  `password`                 VARCHAR(255) NOT NULL COMMENT 'BCrypt hash',
  `secondary_password`       VARCHAR(255)          DEFAULT NULL COMMENT 'BCrypt, C168 optional 6-digit PIN',
  `role`                     ENUM('ADMIN', 'MANAGER', 'SUPERVISOR', 'ACCOUNTANT', 'AUDIT', 'CUSTOMER_SERVICE', 'PARTNERSHIP') NOT NULL,
  `permissions`              JSON                  DEFAULT NULL COMMENT 'Global module permissions',
  `status`                   ENUM('ACTIVE', 'INACTIVE') NOT NULL DEFAULT 'ACTIVE',
  `read_only`                TINYINT(1) NOT NULL DEFAULT 1,
  `remember_token`           VARCHAR(64)           DEFAULT NULL,
  `remember_token_expires`   DATETIME              DEFAULT NULL,
  `last_login`               DATETIME              DEFAULT NULL,
  `created_by`               VARCHAR(50)           DEFAULT NULL,
  `created_at`               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_login_id` (`login_id`),
  UNIQUE KEY `uk_user_email` (`email`),
  KEY `idx_user_status` (`status`),
  KEY `idx_user_remember_token` (`remember_token`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Admin / staff identity';

CREATE TABLE `account` (
  `id`                  INT UNSIGNED NOT NULL AUTO_INCREMENT,
  `account_id`          VARCHAR(255) NOT NULL COMMENT 'Login identifier (Member tab)',
  `name`                VARCHAR(255) NOT NULL,
  `password`            VARCHAR(255) NOT NULL COMMENT 'BCrypt hash',
  `role`                VARCHAR(50)  NOT NULL COMMENT 'CAPITAL, BANK, AGENT, MEMBER, DEBTOR, ...',
  `status`              ENUM('ACTIVE', 'INACTIVE') NOT NULL DEFAULT 'ACTIVE',
  `created_source`      VARCHAR(50)           DEFAULT NULL,
  `last_login`          DATETIME              DEFAULT NULL,
  `created_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_account_account_id` (`account_id`),
  KEY `idx_account_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Member identity';

CREATE TABLE `user_tenant_access` (
  `id`                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`             INT UNSIGNED NOT NULL COMMENT 'FK user.id',
  `tenant_id`           INT UNSIGNED NOT NULL COMMENT 'FK tenant.id',
  `capabilities`        JSON                  DEFAULT NULL COMMENT 'Fine-grained grant e.g. GROUP_LEDGER_READ',
  `account_permissions` JSON                  DEFAULT NULL COMMENT 'Subsidiary account ACL when tenant is company',
  `process_permissions` JSON                  DEFAULT NULL COMMENT 'Subsidiary process ACL when tenant is company',
  `created_at`          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_tenant` (`user_id`, `tenant_id`),
  KEY `idx_uta_user_id` (`user_id`),
  KEY `idx_uta_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Admin grants per tenant (type from tenant.tenant_type)';

CREATE TABLE `account_tenant_access` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `account_id` INT UNSIGNED NOT NULL COMMENT 'FK account.id',
  `tenant_id`  INT UNSIGNED NOT NULL COMMENT 'FK tenant.id',
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_account_tenant` (`account_id`, `tenant_id`),
  KEY `idx_ata_account_id` (`account_id`),
  KEY `idx_ata_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Member access per tenant';

CREATE TABLE `password_reset_tac` (
  `email`      VARCHAR(255) NOT NULL COMMENT 'Admin user email (FK user.email logically)',
  `tenant_id`  INT UNSIGNED NOT NULL COMMENT 'FK tenant.id — reset scope for admin/staff',
  `code`       VARCHAR(10)  NOT NULL COMMENT '6-digit verification code',
  `expires_at` DATETIME     NOT NULL COMMENT 'Code expiry (typically 15 minutes)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`email`, `tenant_id`),
  KEY `idx_prt_tenant_id` (`tenant_id`),
  KEY `idx_prt_expires_at` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Password reset TAC for admin users per tenant';

CREATE TABLE `password_reset_tac_owner` (
  `email`      VARCHAR(255) NOT NULL COMMENT 'Owner email',
  `owner_id`   INT UNSIGNED NOT NULL COMMENT 'FK owner.id',
  `code`       VARCHAR(10)  NOT NULL COMMENT '6-digit verification code',
  `expires_at` DATETIME     NOT NULL COMMENT 'Code expiry (typically 15 minutes)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`email`, `owner_id`),
  KEY `idx_prto_owner_id` (`owner_id`),
  KEY `idx_prto_expires_at` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Password reset TAC for domain owners';

CREATE TABLE `currency` (
  `id`          INT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`   INT UNSIGNED NOT NULL COMMENT 'FK tenant.id',
  `code`        VARCHAR(10)  NOT NULL COMMENT 'Business code e.g. MYR, SGD, USD, JPY',
  `name`        VARCHAR(100)          DEFAULT NULL COMMENT 'Optional display label',
  `sync_source` ENUM('MANUAL', 'SUBSIDIARY') NOT NULL DEFAULT 'MANUAL' COMMENT 'subsidiary = auto-synced into group ledger from child companies',
  `created_at`  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_currency_tenant_code` (`tenant_id`, `code`),
  KEY `idx_currency_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Tenant-scoped currency catalog';

CREATE TABLE `account_currency` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `account_id`     INT UNSIGNED NOT NULL COMMENT 'FK ledger account.id (legacy account table PK)',
  `currency_id`    INT UNSIGNED NOT NULL COMMENT 'FK currency.id',
  `display_order`  SMALLINT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'Lower value shown first in UI',
  `created_at`     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_account_currency` (`account_id`, `currency_id`),
  KEY `idx_ac_currency_id` (`currency_id`),
  KEY `idx_ac_account_display` (`account_id`, `display_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Currencies enabled per ledger account with display sort';

CREATE TABLE `announcements` (
  `id`           INT UNSIGNED NOT NULL AUTO_INCREMENT,
  `title`        VARCHAR(500) NOT NULL COMMENT 'Announce Title',
  `content`      text         NOT NULL COMMENT 'Announce Content',
  `company_code` VARCHAR(50)  NOT NULL DEFAULT 'C168' COMMENT 'Company Code,Just C168 Can View',
  `status`       ENUM('ACTIVE','INACTIVE') NOT NULL DEFAULT 'ACTIVE' COMMENT 'Announce Status',
  `created_by`   INT UNSIGNED NOT NULL COMMENT 'Created Acc ID',
  `user_type`    ENUM('USER','OWNER') NOT NULL DEFAULT 'USER',
  `created_at`   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_announcements_company_code` (`company_code`),
  KEY `idx_announcements_status` (`status`),
  KEY `idx_announcements_created_by` (`created_by`),
  KEY `idx_announcements_user_type` (`user_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Announcements';

-- =============================================================================
-- Seed: owner JS + company C168 (GAME module)
-- Login: Company ID = C168, Admin tab, Username = JS, Password = 1, Secondary = 111111
-- =============================================================================
INSERT INTO `owner` (`owner_code`, `name`, `email`, `password`, `secondary_password`, `status`, `created_by`)
VALUES (
  'JS',
  'JS',
  NULL,
  '$2y$10$DwEdouw8eKv1N2zdKD8CEOQvaFTHnkRoE/i2ovfLR0LLnNp2fWP5a',
  '$2y$10$z3znrCXc0zjatUuMJQ67oOCYEHVlDxqrr5ek.2.f0Bz.hYEqHhB0e',
  'ACTIVE',
  'seed'
);

SET @owner_id = LAST_INSERT_ID();

INSERT INTO `tenant` (`tenant_type`, `code`, `name`, `owner_id`, `parent_id`, `expiration_date`, `category_code`, `status`, `created_by`)
VALUES ('COMPANY', 'C168', 'C168', @owner_id, NULL, NULL, JSON_ARRAY('GAME'), 'ACTIVE', 'JS');

SET @tenant_id = LAST_INSERT_ID();

INSERT INTO `feature_module` (`code`, `name`, `sort_order`, `status`)
VALUES ('GAME', 'Game', 1, 'ACTIVE');

SET @module_id = LAST_INSERT_ID();

INSERT INTO `tenant_feature_module` (`tenant_id`, `module_id`)
VALUES (@tenant_id, @module_id);
