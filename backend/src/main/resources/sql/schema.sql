-- =============================================================================
-- Tenant-model login DB schema (testcount).
-- Apply AFTER backend/src/main/resources/schema.sql on dev DB, or standalone
-- when bootstrapping the login module only.
-- FK checks off for the DROP/CREATE block below: the DROP order here does not fully respect FK
-- dependency order (e.g. `process` is dropped before `user_tenant_process_access`, which still
-- references it), and re-enabling too early would fail on a populated dev DB.
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS `submitted_processes`;
DROP TABLE IF EXISTS `data_capture_line`;
DROP TABLE IF EXISTS `data_capture_description`;
DROP TABLE IF EXISTS `data_capture_formula`;
DROP TABLE IF EXISTS `data_capture_draft_cell`;
DROP TABLE IF EXISTS `data_capture_draft`;
DROP TABLE IF EXISTS `data_captures`;
DROP TABLE IF EXISTS `process_submitted`;
DROP TABLE IF EXISTS `process_day`;
DROP TABLE IF EXISTS `process_description_link`;
DROP TABLE IF EXISTS `process`;
DROP TABLE IF EXISTS `process_description`;
DROP TABLE IF EXISTS `description`;
DROP TABLE IF EXISTS `bank_process_resend_daily_guard`;
DROP TABLE IF EXISTS `transactions`;
DROP TABLE IF EXISTS `bank_process_accounting_posted`;
DROP TABLE IF EXISTS `bank_process_share`;
DROP TABLE IF EXISTS `bank_process`;
DROP TABLE IF EXISTS `bank_option`;
DROP TABLE IF EXISTS `bank_country`;
DROP TABLE IF EXISTS `tenant_ownership_history`;
DROP TABLE IF EXISTS `tenant_ownership`;
DROP TABLE IF EXISTS `tenant_auto_renew_request`;
DROP TABLE IF EXISTS `account_currency`;
DROP TABLE IF EXISTS `currency`;
DROP TABLE IF EXISTS `maintenance_marquee`;
DROP TABLE IF EXISTS `announcements`;
DROP TABLE IF EXISTS `domain_list_fee_price`;
DROP TABLE IF EXISTS `domain_list_fee_settings`;
DROP TABLE IF EXISTS `renewal_period`;
DROP TABLE IF EXISTS `tenant_link`;
DROP TABLE IF EXISTS `tenant_fee_share_allocation`;
DROP TABLE IF EXISTS `tenant_feature_module`;
DROP TABLE IF EXISTS `user_role_permission`;
DROP TABLE IF EXISTS `permission`;
DROP TABLE IF EXISTS `feature_module`;
DROP TABLE IF EXISTS `password_reset_tac`;
DROP TABLE IF EXISTS `password_reset_tac_owner`;
DROP TABLE IF EXISTS `user_tenant_process_access`;
DROP TABLE IF EXISTS `user_tenant_account_access`;
DROP TABLE IF EXISTS `account_tenant_access`;
DROP TABLE IF EXISTS `account_link`;
DROP TABLE IF EXISTS `user_tenant_access`;
DROP TABLE IF EXISTS `tenant`;
DROP TABLE IF EXISTS `account`;
DROP TABLE IF EXISTS `user`;
DROP TABLE IF EXISTS `user_role`;
DROP TABLE IF EXISTS `owner`;

CREATE TABLE `owner` (
    `id` int(10) unsigned NOT NULL AUTO_INCREMENT,
    `owner_code` varchar(50) NOT NULL COMMENT 'Login identifier (Admin tab)',
    `name` varchar(150) NOT NULL,
    `email` varchar(150) DEFAULT NULL,
    `password` varchar(255) NOT NULL COMMENT 'BCrypt hash',
    `secondary_password` varchar(255) DEFAULT NULL COMMENT 'BCrypt hash, 6-digit PIN',
    `status` enum('ACTIVE','INACTIVE') NOT NULL DEFAULT 'ACTIVE',
    `created_by` varchar(50) DEFAULT NULL,
    `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_owner_code` (`owner_code`),
KEY `idx_owner_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Domain owner identity';

CREATE TABLE `account` (
    `id`                  INT UNSIGNED NOT NULL AUTO_INCREMENT,
    `account_id`          VARCHAR(255) NOT NULL COMMENT 'Login identifier (Member tab)',
    `name`                VARCHAR(255) NOT NULL,
    `password`            VARCHAR(255) NOT NULL COMMENT 'BCrypt hash',
    `role`                VARCHAR(50)  NOT NULL COMMENT 'CAPITAL, BANK, AGENT, MEMBER, DEBTOR, ...',
    `status`              ENUM('ACTIVE','INACTIVE') NOT NULL DEFAULT 'ACTIVE',
    `created_source`      VARCHAR(50)    DEFAULT NULL COMMENT 'Account source, e.g. domain_auto/manual',
    `payment_alert`       TINYINT(1)   NOT NULL DEFAULT 0 COMMENT 'Payment alert ON/OFF',
    `alert_day`           VARCHAR(255)   DEFAULT NULL COMMENT 'Alert type: weekly, monthly, or day 1-31',
    `alert_specific_date` DATE           DEFAULT NULL COMMENT 'Alert start date (YYYY-MM-DD)',
    `alert_amount`        DECIMAL(25, 8) DEFAULT NULL COMMENT 'Alert amount threshold',
    `remark`              TEXT           DEFAULT NULL COMMENT 'Account remark',
    `last_login`          DATETIME       DEFAULT NULL,
    `created_at` datetime NOT NULL DEFAULT current_timestamp(),
    PRIMARY KEY (`id`),
    KEY `idx_account_account_id` (`account_id`),
    KEY `idx_account_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Member identity';
-- account_id is unique per tenant (account_tenant_access), not globally — see UserServiceImpl.createUser

-- =============================================================================
-- Admin / staff role dictionary
-- hierarchy_level: lower value = higher privilege (aligned with frontend ROLE_HIERARCHY)
-- =============================================================================
CREATE TABLE `user_role` (
 `id`              TINYINT UNSIGNED NOT NULL AUTO_INCREMENT,
 `code`            VARCHAR(50)  NOT NULL COMMENT 'Machine code e.g. ADMIN, CUSTOMER_SERVICE',
 `name`            VARCHAR(100) NOT NULL COMMENT 'Display name',
 `hierarchy_level` TINYINT UNSIGNED NOT NULL COMMENT 'Lower = higher privilege',
 `status`          ENUM('ACTIVE', 'INACTIVE') NOT NULL DEFAULT 'ACTIVE',
 `created_at`      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
 `updated_at`      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 PRIMARY KEY (`id`),
 UNIQUE KEY `uk_role_code` (`code`),
 KEY `idx_role_status` (`status`),
 KEY `idx_role_hierarchy` (`hierarchy_level`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Admin / staff role dictionary';

INSERT INTO `user_role` (`id`, `code`, `name`, `hierarchy_level`, `status`) VALUES
(1, 'OWNER',            'Owner',             1, 'ACTIVE'),
(2, 'ADMIN',            'Admin',             2, 'ACTIVE'),
(3, 'MANAGER',          'Manager',           3, 'ACTIVE'),
(4, 'SUPERVISOR',       'Supervisor',        4, 'ACTIVE'),
(5, 'ACCOUNTANT',       'Accountant',        5, 'ACTIVE'),
(6, 'AUDIT',            'Audit',             6, 'ACTIVE'),
(7, 'CUSTOMER_SERVICE', 'Customer Service',  7, 'ACTIVE'),
(8, 'PARTNERSHIP',      'Partnership',       8, 'ACTIVE');

CREATE TABLE `feature_module` (
  `id`         SMALLINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `code`       VARCHAR(50)  NOT NULL COMMENT 'Canonical module code e.g. GAME, BANK, LOAN',
  `name`       VARCHAR(255) NOT NULL COMMENT 'Display name',
  `sort_order` SMALLINT     NOT NULL DEFAULT 0,
  `status`     ENUM('ACTIVE', 'INACTIVE') NOT NULL DEFAULT 'ACTIVE',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_feature_module_code` (`code`),
  KEY `idx_feature_module_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Business module dictionary';

INSERT INTO `feature_module` (`id`, `code`, `name`, `sort_order`, `status`) VALUES
(1, 'GAME',  'Games', 1, 'ACTIVE'),
(2, 'BANK',  'Bank',  2, 'ACTIVE'),
(3, 'LOAN',  'Loan',  3, 'ACTIVE'),
(4, 'RATE',  'Rate',  4, 'ACTIVE'),
(5, 'MONEY', 'Money', 5, 'ACTIVE');

-- =============================================================================
-- Sidebar permission dictionary
-- DOMAIN / ANNOUNCEMENTS: injected at runtime for C168 (not bound to roles)
-- REPORT: requires tenant GAME feature (requires_feature_id)
-- =============================================================================
CREATE TABLE `permission` (
  `id`                  SMALLINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `code`                VARCHAR(50)  NOT NULL COMMENT 'HOME, DOMAIN, ADMIN ...',
  `name`                VARCHAR(100) NOT NULL COMMENT 'Display name',
  `sort_order`          SMALLINT     NOT NULL DEFAULT 0,
  `requires_feature_id` SMALLINT UNSIGNED DEFAULT NULL COMMENT 'FK feature_module.id; NULL = no tenant gate',
  `status`              ENUM('ACTIVE', 'INACTIVE') NOT NULL DEFAULT 'ACTIVE',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_permission_code` (`code`),
  KEY `idx_permission_status` (`status`),
  KEY `idx_permission_requires_feature` (`requires_feature_id`),
  CONSTRAINT `fk_permission_requires_feature`
      FOREIGN KEY (`requires_feature_id`) REFERENCES `feature_module` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Sidebar permission dictionary';

INSERT INTO `permission` (`id`, `code`, `name`, `sort_order`, `requires_feature_id`, `status`) VALUES
( 1, 'HOME',          'Home',          1,  NULL, 'ACTIVE'),
( 2, 'DOMAIN',        'Domain',        2,  NULL, 'ACTIVE'),
( 3, 'ANNOUNCEMENTS', 'Announcements', 3,  NULL, 'ACTIVE'),
( 4, 'ADMIN',         'Admin',         4,  NULL, 'ACTIVE'),
( 5, 'ACCOUNT',       'Account',       5,  NULL, 'ACTIVE'),
( 6, 'OWNERSHIP',     'Ownership',     6,  NULL, 'ACTIVE'),
( 7, 'PROCESS',       'Process',       7,  NULL, 'ACTIVE'),
( 8, 'DATACAPTURE',   'Data Capture',  8,  NULL, 'ACTIVE'),
( 9, 'PAYMENT',       'Payment',       9,  NULL, 'ACTIVE'),
(10, 'REPORT',        'Report',        10, 1,    'ACTIVE'),
(11, 'MAINTENANCE',   'Maintenance',   11, NULL, 'ACTIVE');

-- Default sidebar per role (DOMAIN / ANNOUNCEMENTS excluded — C168 runtime only)
CREATE TABLE `user_role_permission` (
    `role_id`       TINYINT UNSIGNED NOT NULL,
    `permission_id` SMALLINT UNSIGNED NOT NULL,
    PRIMARY KEY (`role_id`, `permission_id`),
    KEY `idx_urp_permission_id` (`permission_id`),
    CONSTRAINT `fk_urp_role` FOREIGN KEY (`role_id`) REFERENCES `user_role` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_urp_permission` FOREIGN KEY (`permission_id`) REFERENCES `permission` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Default sidebar permissions per admin role';

INSERT INTO `user_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id
FROM `user_role` r
         JOIN `permission` p ON p.code IN (
         'HOME', 'ADMIN', 'ACCOUNT', 'OWNERSHIP', 'PROCESS', 'DATACAPTURE', 'PAYMENT', 'REPORT', 'MAINTENANCE'
    )
WHERE r.code IN ('OWNER', 'PARTNERSHIP', 'ADMIN');

INSERT INTO `user_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id
FROM `user_role` r
         JOIN `permission` p ON p.code IN (
         'ADMIN', 'ACCOUNT', 'PROCESS', 'DATACAPTURE', 'PAYMENT', 'REPORT', 'MAINTENANCE'
    )
WHERE r.code = 'MANAGER';

INSERT INTO `user_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id
FROM `user_role` r
         JOIN `permission` p ON p.code IN (
        'ADMIN', 'ACCOUNT', 'PROCESS', 'DATACAPTURE', 'PAYMENT', 'REPORT'
    )
WHERE r.code = 'SUPERVISOR';

INSERT INTO `user_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id
FROM `user_role` r
         JOIN `permission` p ON p.code IN ('ACCOUNT', 'PROCESS', 'PAYMENT', 'REPORT')
WHERE r.code = 'ACCOUNTANT';

INSERT INTO `user_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id
FROM `user_role` r
         JOIN `permission` p ON p.code IN ('PAYMENT', 'REPORT', 'MAINTENANCE')
WHERE r.code = 'AUDIT';

INSERT INTO `user_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id
FROM `user_role` r
         JOIN `permission` p ON p.code IN (
                                           'ACCOUNT', 'PROCESS', 'DATACAPTURE', 'PAYMENT', 'REPORT'
    )
WHERE r.code = 'CUSTOMER_SERVICE';

CREATE TABLE `user` (
    `id`                     INT UNSIGNED NOT NULL AUTO_INCREMENT,
    `login_id`               VARCHAR(50)  NOT NULL COMMENT 'Login identifier (Admin tab)',
    `name`                   VARCHAR(100) NOT NULL,
    `email`                  VARCHAR(100) NOT NULL,
    `password`               VARCHAR(255) NOT NULL COMMENT 'BCrypt hash',
    `secondary_password`     VARCHAR(255)          DEFAULT NULL COMMENT 'BCrypt, C168 optional 6-digit PIN',
    `role_id`                TINYINT UNSIGNED NOT NULL COMMENT 'FK user_role.id',
    `status`                 ENUM('ACTIVE', 'INACTIVE') NOT NULL DEFAULT 'ACTIVE',
    `read_only`              TINYINT(1)   NOT NULL DEFAULT 1,
    `remember_token`         VARCHAR(64)           DEFAULT NULL,
    `remember_token_expires` DATETIME              DEFAULT NULL,
    `last_login`             DATETIME              DEFAULT NULL,
    `created_by`             VARCHAR(50)           DEFAULT NULL,
    `created_at`             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_login_id` (`login_id`),
    UNIQUE KEY `uk_user_email` (`email`),
    KEY `idx_user_status` (`status`),
    KEY `idx_user_role_id` (`role_id`),
CONSTRAINT `fk_user_role` FOREIGN KEY (`role_id`) REFERENCES `user_role` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Admin / staff identity';

CREATE TABLE `tenant` (
  `id`                INT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_type`       ENUM('GROUP', 'COMPANY') NOT NULL,
  `code`              VARCHAR(50)  NOT NULL COMMENT 'Login / business code e.g. AP, 95, C168',
  `name`              VARCHAR(150)          DEFAULT NULL,
  `owner_id`          INT UNSIGNED          DEFAULT NULL COMMENT 'FK owner.id',
  `parent_id`         INT UNSIGNED          DEFAULT NULL COMMENT 'company → parent group tenant.id',
  `expiration_date`   DATE                  DEFAULT NULL COMMENT 'Per-tenant expiry (group or company)',
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

CREATE TABLE `tenant_feature_module` (
 `id`         INT UNSIGNED NOT NULL AUTO_INCREMENT,
 `tenant_id`  INT UNSIGNED NOT NULL COMMENT 'FK tenant.id',
 `module_id`  SMALLINT UNSIGNED NOT NULL COMMENT 'FK feature_module.id',
 `created_at` TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY (`id`),
 UNIQUE KEY `uk_tenant_feature_module` (`tenant_id`, `module_id`),
 KEY `idx_tfm_tenant_id` (`tenant_id`),
 KEY `idx_tfm_module_id` (`module_id`),
 CONSTRAINT `fk_tfm_tenant`
     FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`) ON DELETE CASCADE,
 CONSTRAINT `fk_tfm_module`
     FOREIGN KEY (`module_id`) REFERENCES `feature_module` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Tenant business modules';

CREATE TABLE `tenant_fee_share_allocation` (
   `id`                INT UNSIGNED NOT NULL AUTO_INCREMENT,
   `tenant_id`         INT UNSIGNED NOT NULL COMMENT 'FK tenant.id',
   `share_type`        ENUM('SALES', 'CS', 'IT', 'PROFIT') NOT NULL COMMENT 'Fee share category',
   `account_id`        INT UNSIGNED DEFAULT NULL COMMENT 'Shareholder account (owner.id or user.id)',
   `owner_type`        ENUM('owner', 'user', 'group') NOT NULL DEFAULT 'owner' COMMENT 'Account identity type',
   `partner_tenant_id` INT UNSIGNED DEFAULT NULL COMMENT 'When owner_type=group, FK partner tenant.id',
   `percentage`        DECIMAL(7, 4) NOT NULL DEFAULT 0.0000 COMMENT 'Share percentage (e.g. 33.3333)',
   `sort_order`        INT NOT NULL DEFAULT 0,
   PRIMARY KEY (`id`),
   UNIQUE KEY `uk_tfsa` (`tenant_id`, `share_type`, `account_id`, `owner_type`, `partner_tenant_id`),
   KEY `idx_tfsa_tenant_id` (`tenant_id`),
   CONSTRAINT `fk_tfsa_tenant`
       FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`) ON DELETE CASCADE,
   CONSTRAINT `fk_tfsa_partner_tenant`
       FOREIGN KEY (`partner_tenant_id`) REFERENCES `tenant` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Per-tenant fee share rows (replaces tenant.fee_share_allocations JSON)';

CREATE TABLE `tenant_link` (
   `id`               INT UNSIGNED NOT NULL AUTO_INCREMENT,
   `tenant_id`        INT UNSIGNED NOT NULL COMMENT 'FK tenant.id (usually a group)',
   `linked_tenant_id` INT UNSIGNED NOT NULL COMMENT 'FK tenant.id (partner group)',
   `link_type`        VARCHAR(32)  NOT NULL DEFAULT 'partner' COMMENT 'partner, aggregate, ...',
   `created_at`       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
   PRIMARY KEY (`id`),
   UNIQUE KEY `uk_tenant_link_pair` (`tenant_id`, `linked_tenant_id`),
   KEY `idx_tenant_link_linked` (`linked_tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Peer links between group tenants (AP+IG etc.)';

CREATE TABLE `user_tenant_access` (
  `id`                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`             INT UNSIGNED NOT NULL COMMENT 'FK user.id',
  `tenant_id`           INT UNSIGNED NOT NULL COMMENT 'FK tenant.id',
  `account_acl_mode`    ENUM('ALL', 'CUSTOM', 'NONE') NOT NULL DEFAULT 'ALL' COMMENT 'Account visibility: ALL = full access, CUSTOM = use user_tenant_account_access, NONE = deny all',
  `process_acl_mode`    ENUM('ALL', 'CUSTOM', 'NONE') NOT NULL DEFAULT 'ALL' COMMENT 'Process visibility: ALL = full access, CUSTOM = use user_tenant_process_access, NONE = deny all',
  `created_at`          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_tenant` (`user_id`, `tenant_id`),
  KEY `idx_uta_user_id` (`user_id`),
  KEY `idx_uta_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Admin grants per tenant (type from tenant.tenant_type)';

CREATE TABLE `user_tenant_account_access` (
    `id`                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `user_tenant_access_id` BIGINT UNSIGNED NOT NULL COMMENT 'FK user_tenant_access.id',
    `account_id`            INT UNSIGNED NOT NULL COMMENT 'FK account.id (visible account scope)',
    `created_at`            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_utaa_access_account` (`user_tenant_access_id`, `account_id`),
    KEY `idx_utaa_access_id` (`user_tenant_access_id`),
    KEY `idx_utaa_account_id` (`account_id`),
    CONSTRAINT `fk_utaa_access`
      FOREIGN KEY (`user_tenant_access_id`) REFERENCES `user_tenant_access` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_utaa_account`
      FOREIGN KEY (`account_id`) REFERENCES `account` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Per-user per-tenant account ACL (normalized replacement for JSON account_permissions)';

-- user_tenant_process_access (FK → process) is created further below, right after the `process`
-- table — process is not defined yet at this point in the script.

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

CREATE TABLE `account_link` (
    `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `account_id_1`      INT UNSIGNED NOT NULL COMMENT 'FK account.id (smaller ID)',
    `account_id_2`      INT UNSIGNED NOT NULL COMMENT 'FK account.id (larger ID)',
    `tenant_id`         INT UNSIGNED NOT NULL COMMENT 'FK tenant.id',
    `link_type`         ENUM('BIDIRECTIONAL', 'UNIDIRECTIONAL') NOT NULL DEFAULT 'BIDIRECTIONAL',
    `source_account_id` INT UNSIGNED DEFAULT NULL COMMENT 'For unidirectional, defines the link source',
    `created_at`        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_account_link_pair` (`account_id_1`, `account_id_2`, `tenant_id`),
    KEY `idx_al_tenant_id` (`tenant_id`),
    KEY `idx_al_account_1` (`account_id_1`),
KEY `idx_al_account_2` (`account_id_2`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Linking between member accounts';

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
    `email`      VARCHAR(255) NOT NULL COMMENT 'Owner email (FK owner.email logically)',
    `tenant_id`  INT UNSIGNED NOT NULL COMMENT 'FK tenant.id — reset scope for domain owner',
    `code`       VARCHAR(10)  NOT NULL COMMENT '6-digit verification code',
    `expires_at` DATETIME     NOT NULL COMMENT 'Code expiry (typically 15 minutes)',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`email`, `tenant_id`),
    KEY `idx_prto_tenant_id` (`tenant_id`),
    KEY `idx_prto_expires_at` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Password reset TAC for domain owners';

-- Renewal period dictionary (shared by domain_list_fee_price and tenant_auto_renew_request)
CREATE TABLE `renewal_period` (
  `code`       VARCHAR(20)  NOT NULL COMMENT 'Machine code e.g. 7days, 1month, 1year',
  `sort_order` SMALLINT UNSIGNED NOT NULL,
  `label`      VARCHAR(50)  NOT NULL COMMENT 'Display label',
  PRIMARY KEY (`code`),
  KEY `idx_renewal_period_sort` (`sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Auto-renew period dictionary';

INSERT INTO `renewal_period` (`code`, `sort_order`, `label`) VALUES
('7days',   1, '7 Days'),
('1month',  2, '1 Month'),
('3months', 3, '3 Months'),
('6months', 4, '6 Months'),
('1year',   5, '1 Year');

-- One row per tenant_type + period (replaces domain_list_fee_settings JSON columns)
CREATE TABLE `domain_list_fee_price` (
 `tenant_type` ENUM('GROUP', 'COMPANY') NOT NULL COMMENT 'GROUP or COMPANY list fee',
 `period`      VARCHAR(20)  NOT NULL COMMENT 'FK renewal_period.code',
 `price`       DECIMAL(25, 8) NOT NULL DEFAULT 0 COMMENT 'Price for this period',
 `updated_at`  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 PRIMARY KEY (`tenant_type`, `period`),
 KEY `idx_dlfp_period` (`period`),
 CONSTRAINT `fk_dlfp_period`
     FOREIGN KEY (`period`) REFERENCES `renewal_period` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='C168 global domain list fee / auto-renew prices (normalized)';

INSERT INTO `domain_list_fee_price` (`tenant_type`, `period`, `price`)
SELECT tt.tenant_type, rp.code, 0
FROM (
         SELECT 'GROUP' AS tenant_type
         UNION ALL
         SELECT 'COMPANY'
     ) AS tt
         CROSS JOIN `renewal_period` AS rp;

CREATE TABLE `announcements` (
 `id`           INT UNSIGNED NOT NULL AUTO_INCREMENT,
 `title`        VARCHAR(500) NOT NULL COMMENT 'Announcement title',
 `content`      TEXT         NOT NULL COMMENT 'Announcement body',
 `company_code` VARCHAR(50)  NOT NULL DEFAULT 'C168' COMMENT 'Scope: C168 announcements only',
 `status`       ENUM('ACTIVE', 'INACTIVE') NOT NULL DEFAULT 'ACTIVE' COMMENT 'Publication status',
 `created_by`   INT UNSIGNED NOT NULL COMMENT 'FK user.id or owner.id (see user_type)',
 `user_type`    ENUM('USER', 'OWNER') NOT NULL DEFAULT 'USER' COMMENT 'Creator identity table',
 `created_at`   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
 `updated_at`   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 PRIMARY KEY (`id`),
 KEY `idx_announcements_company_code` (`company_code`),
 KEY `idx_announcements_status` (`status`),
 KEY `idx_announcements_created_at` (`created_at`),
 KEY `idx_announcements_created_by` (`created_by`),
 KEY `idx_announcements_user_type_created_by` (`user_type`, `created_by`),
 KEY `idx_announcements_dashboard` (`company_code`, `status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='C168 system announcements';

CREATE TABLE `maintenance_marquee` (
   `id`           INT UNSIGNED NOT NULL AUTO_INCREMENT,
   `prefix`       VARCHAR(100) NOT NULL DEFAULT '' COMMENT 'Marquee label shown before content',
   `content`      TEXT         NOT NULL COMMENT 'Maintenance marquee body',
   `company_code` VARCHAR(50)  NOT NULL DEFAULT 'C168' COMMENT 'Scope: C168 maintenance only',
   `status`       ENUM('ACTIVE', 'INACTIVE') NOT NULL DEFAULT 'ACTIVE' COMMENT 'Publication status',
   `created_by`   INT UNSIGNED NOT NULL COMMENT 'FK user.id or owner.id (see user_type)',
   `user_type`    ENUM('USER', 'OWNER') NOT NULL DEFAULT 'USER' COMMENT 'Creator identity table',
   `created_at`   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
   `updated_at`   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
   PRIMARY KEY (`id`),
   KEY `idx_maintenance_company_code` (`company_code`),
   KEY `idx_maintenance_status` (`status`),
   KEY `idx_maintenance_created_at` (`created_at`),
   KEY `idx_maintenance_active_lookup` (`company_code`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='C168 login-page maintenance marquee';

CREATE TABLE `currency` (
    `id`          INT UNSIGNED NOT NULL AUTO_INCREMENT,
    `tenant_id`   INT UNSIGNED NOT NULL COMMENT 'FK tenant.id (GROUP or COMPANY ledger)',
    `code`        VARCHAR(10)  NOT NULL COMMENT 'Currency code e.g. MYR, SGD, USD',
    `sync_source` ENUM('MANUAL', 'SUBSIDIARY') NOT NULL DEFAULT 'MANUAL' COMMENT 'SUBSIDIARY = auto-synced into a group tenant from subsidiaries',
    `status`      ENUM('ACTIVE', 'INACTIVE') NOT NULL DEFAULT 'ACTIVE',
    `created_at`  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_currency_tenant_code` (`tenant_id`, `code`),
    KEY `idx_currency_tenant_id` (`tenant_id`),
    KEY `idx_currency_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Tenant-scoped currency master (replaces company_id + scope_type + scope_id)';

CREATE TABLE `account_currency` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `account_id`  INT UNSIGNED NOT NULL COMMENT 'FK account.id',
    `tenant_id`   INT UNSIGNED NOT NULL COMMENT 'FK tenant.id — same scope as account_tenant_access',
    `currency_id` INT UNSIGNED NOT NULL COMMENT 'FK currency.id (currency.tenant_id must match tenant_id)',
    `sort_order`  SMALLINT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'Display order (replaces account_currency_display_order)',
    `created_at`  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_account_tenant_currency` (`account_id`, `tenant_id`, `currency_id`),
    KEY `idx_ac_account_tenant` (`account_id`, `tenant_id`),
    KEY `idx_ac_currency_id` (`currency_id`),
    KEY `idx_ac_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Account enabled currencies per tenant';

CREATE TABLE `tenant_auto_renew` (
 `id`                  INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 `tenant_id`           INT UNSIGNED NOT NULL COMMENT 'FK tenant.id',
 `expiration_snapshot` DATE         NOT NULL COMMENT '发起申请时的到期时间快照',
 `status`              ENUM('pending', 'approved', 'rejected') NOT NULL DEFAULT 'pending' COMMENT '审批状态',
 `period`              VARCHAR(20)  DEFAULT NULL COMMENT '续费周期 (e.g. 7days, 1month, 3months, 6months, 1year)',
 `price`               DECIMAL(25, 8) DEFAULT NULL COMMENT '应付价格',
 `new_expiration_date` DATE         DEFAULT NULL COMMENT '审批通过后的新到期时间',
 `processed_by`        VARCHAR(50)  DEFAULT NULL COMMENT '执行审批的管理员账号/ID',
 `processed_at`        DATETIME     DEFAULT NULL COMMENT '审批处理的具体时间',
 `created_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP(),
 `updated_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP() ON UPDATE CURRENT_TIMESTAMP(),
 UNIQUE KEY `uk_tenant_expiration` (`tenant_id`, `expiration_snapshot`),
 CONSTRAINT `fk_tar_tenant` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`) ON DELETE CASCADE,
 KEY `idx_tar_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户自动续期审批申请表';

CREATE TABLE `tenant_auto_renew_transaction` (
 `id`             INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 `request_id`     INT UNSIGNED NOT NULL COMMENT 'FK tenant_auto_renew_request.id',
 `transaction_id` INT UNSIGNED NOT NULL COMMENT 'FK transactions.id — approve 时 chargeDomainFee 生成的其中一条流水（付款/佣金/净利润，一个 request 可对应多条）',
 `created_at`     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY `uk_tart_request_transaction` (`request_id`, `transaction_id`),
 KEY `idx_tart_request` (`request_id`),
 KEY `idx_tart_transaction` (`transaction_id`),
 CONSTRAINT `fk_tart_request` FOREIGN KEY (`request_id`) REFERENCES `tenant_auto_renew_request` (`id`) ON DELETE CASCADE,
 CONSTRAINT `fk_tart_transaction` FOREIGN KEY (`transaction_id`) REFERENCES `transactions` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Auto Renew approve 时生成的 transactions 关联记录，供 delete/revert 时精确定位并删除';

CREATE TABLE `tenant_ownership` (
    `id`                 INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `tenant_id`          INT UNSIGNED NOT NULL COMMENT '关联 tenant.id (主公司或集团)',
    `account_id`         INT UNSIGNED DEFAULT NULL COMMENT '股东账号ID（关联 owner.id 或 user.id）。如果股东本身是另一个集团，则此列可留空',
    `owner_type`         ENUM('owner', 'user', 'group') NOT NULL DEFAULT 'owner' COMMENT '股东类型',
    `partner_tenant_id`  INT UNSIGNED DEFAULT NULL COMMENT '当 owner_type=''group'' 时，关联对方的 tenant.id',
    `percentage`         DECIMAL(7, 4) NOT NULL DEFAULT 0.0000 COMMENT '占股比例（百分比，支持4位小数，如 33.3333）',
    `read_only`          TINYINT(1) NOT NULL DEFAULT 1,
    `sort_order`         INT NOT NULL DEFAULT 0,
    UNIQUE KEY `uq_tenant_owner_account` (`tenant_id`, `account_id`, `owner_type`, `partner_tenant_id`),
    CONSTRAINT `fk_to_tenant` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_to_partner_tenant` FOREIGN KEY (`partner_tenant_id`) REFERENCES `tenant` (`id`) ON DELETE SET NULL,
    KEY `idx_to_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户股权分配表(实时)';

CREATE TABLE `tenant_ownership_history` (
    `id`               INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `tenant_id`        INT UNSIGNED NOT NULL COMMENT '关联 tenant.id',
    `effective_month`  DATE NOT NULL COMMENT '快照月份首日 YYYY-MM-01',
    `account_id`       INT UNSIGNED DEFAULT NULL COMMENT '股东账号ID',
    `owner_type`       ENUM('owner', 'user', 'group') NOT NULL DEFAULT 'owner',
    `partner_tenant_id` INT UNSIGNED DEFAULT NULL COMMENT '关联对方的 tenant.id',
    `percentage`       DECIMAL(7, 4) NOT NULL DEFAULT 0.0000,
    `read_only`        TINYINT(1) NOT NULL DEFAULT 1,
    `saved_by`         VARCHAR(50) DEFAULT NULL COMMENT '操作人 login_id（admin=user.login_id；owner=owner_code）',
    `saved_at`         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP(),
    UNIQUE KEY `uq_tenant_oh_month_account` (`tenant_id`, `effective_month`, `account_id`, `owner_type`, `partner_tenant_id`),
    CONSTRAINT `fk_toh_tenant` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_toh_partner_tenant` FOREIGN KEY (`partner_tenant_id`) REFERENCES `tenant` (`id`) ON DELETE SET NULL,
    KEY `idx_toh_tenant_month` (`tenant_id`, `effective_month`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户股权历史月度快照表';

-- =============================================================================
-- =============================================================================
-- Core Process Tables (Optimized Tenant-Model, no JSON)
-- =============================================================================

CREATE TABLE `process_description` (
   `id`         INT UNSIGNED NOT NULL AUTO_INCREMENT,
   `tenant_id`  INT UNSIGNED NOT NULL COMMENT 'FK tenant.id',
   `name`       VARCHAR(255) NOT NULL COMMENT '描述名称/模板内容',
   `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
   PRIMARY KEY (`id`),
   CONSTRAINT `fk_process_description_tenant` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`) ON DELETE CASCADE,
   KEY `idx_description_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='交易描述库/模板表';

CREATE TABLE `process` (
   `id`                INT UNSIGNED NOT NULL AUTO_INCREMENT,
   `tenant_id`         INT UNSIGNED NOT NULL COMMENT 'FK tenant.id',
   `category`          ENUM('GAME', 'BANK') NOT NULL DEFAULT 'GAME' COMMENT 'GAME=动态 process+day+submitted 过滤；BANK=固定四码且 option 常显',
   `code`              VARCHAR(50) NOT NULL COMMENT '业务码；BANK 固定 PROFIT/SALARY/COMMISSION/BONUS',
   `currency_id`       INT UNSIGNED NOT NULL COMMENT '默认币别 FK currency.id',
   `remove_word`       TEXT DEFAULT NULL COMMENT '要过滤的词，逗号分隔（GAME）',
   `replace_word_from` VARCHAR(255) DEFAULT NULL COMMENT 'GAME',
   `replace_word_to`   VARCHAR(255) DEFAULT NULL COMMENT 'GAME',
   `remark`            TEXT DEFAULT NULL COMMENT 'process 配置备注（非 Data Capture 表单 remark）',
   `status`            ENUM('ACTIVE', 'INACTIVE') NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE=启用, INACTIVE=停用',
   `created_by`        VARCHAR(50) DEFAULT NULL COMMENT '创建人 login_id（admin=user.login_id；owner=owner_code）',
   `updated_by`        VARCHAR(50) DEFAULT NULL COMMENT '修改人 login_id（同上）',
   `created_at`        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
   `updated_at`        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
   PRIMARY KEY (`id`),
   UNIQUE KEY `uk_process_tenant_category_code` (`tenant_id`, `category`, `code`),
   CONSTRAINT `fk_process_tenant` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`) ON DELETE CASCADE,
   CONSTRAINT `fk_process_currency` FOREIGN KEY (`currency_id`) REFERENCES `currency` (`id`),
   KEY `idx_process_tenant_id` (`tenant_id`),
   KEY `idx_process_tenant_category` (`tenant_id`, `category`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='流程配置表（无 JSON；GAME/BANK 同表用 category 区分规则）';

-- Moved here (was declared right after user_tenant_account_access) because it FKs into `process`,
-- which is not defined until this point in the script.
CREATE TABLE `user_tenant_process_access` (
  `id`                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_tenant_access_id` BIGINT UNSIGNED NOT NULL COMMENT 'FK user_tenant_access.id',
  `process_id`            INT UNSIGNED NOT NULL COMMENT 'FK process.id (visible process scope)',
  `created_at`            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_utpa_access_process` (`user_tenant_access_id`, `process_id`),
  KEY `idx_utpa_access_id` (`user_tenant_access_id`),
  KEY `idx_utpa_process_id` (`process_id`),
  CONSTRAINT `fk_utpa_access`
      FOREIGN KEY (`user_tenant_access_id`) REFERENCES `user_tenant_access` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_utpa_process`
      FOREIGN KEY (`process_id`) REFERENCES `process` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Per-user per-tenant process ACL (normalized replacement for JSON process_permissions)';

CREATE TABLE `process_description_link` (
    `id`             INT UNSIGNED NOT NULL AUTO_INCREMENT,
    `process_id`     INT UNSIGNED NOT NULL COMMENT 'FK process.id',
    `description_id` INT UNSIGNED NOT NULL COMMENT 'FK process_description.id',
    `created_at`     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_proc_desc` (`process_id`, `description_id`),
    KEY `idx_pdl_description` (`description_id`),
    CONSTRAINT `fk_pdl_process` FOREIGN KEY (`process_id`) REFERENCES `process` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_pdl_description` FOREIGN KEY (`description_id`) REFERENCES `process_description` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='process ↔ description 多对多（替代 description_ids JSON）';

CREATE TABLE `process_day` (
   `id`          INT UNSIGNED NOT NULL AUTO_INCREMENT,
   `process_id`  INT UNSIGNED NOT NULL COMMENT 'FK process.id',
   `day_of_week` TINYINT UNSIGNED NOT NULL COMMENT '1=Mon ... 7=Sun',
   PRIMARY KEY (`id`),
   UNIQUE KEY `uk_process_day` (`process_id`, `day_of_week`),
   CONSTRAINT `fk_pd_process` FOREIGN KEY (`process_id`) REFERENCES `process` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='process 运行星期（替代 schedule_days JSON）';

CREATE TABLE `process_submitted` (
 `id`           INT UNSIGNED NOT NULL AUTO_INCREMENT,
 `tenant_id`    INT UNSIGNED NOT NULL COMMENT 'FK tenant.id',
 `process_id`   INT UNSIGNED NOT NULL COMMENT 'FK process.id',
 `created_by`   VARCHAR(50) DEFAULT NULL COMMENT '操作人 login_id（admin=user.login_id；owner=owner_code）',
 `capture_date` DATE NOT NULL COMMENT '业务捕获日期',
 `created_at`   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 `capture_id`   INT UNSIGNED DEFAULT NULL COMMENT 'FK data_captures.id — 产生本条标记的那次提交；Capture Maintenance 按 capture 整体软删时据此清掉标记。历史行可能为 NULL（补加字段前的数据）',
 PRIMARY KEY (`id`),
 CONSTRAINT `fk_sp_tenant` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`) ON DELETE CASCADE,
 CONSTRAINT `fk_sp_process` FOREIGN KEY (`process_id`) REFERENCES `process` (`id`) ON DELETE CASCADE,
 CONSTRAINT `fk_sp_capture` FOREIGN KEY (`capture_id`) REFERENCES `data_captures` (`id`) ON DELETE SET NULL,
 KEY `idx_sp_tenant_process_date` (`tenant_id`, `process_id`, `capture_date`),
 KEY `idx_sp_tenant_capture_date` (`tenant_id`, `capture_date`),
 KEY `idx_sp_capture` (`capture_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='已提交记录：GAME/BANK Submit 都会写一行。GAME 当日同 process 去重靠 service 层 existsProcessSubmitted 挡（不再靠 DB 唯一键）；BANK 允许同一 process 同一天多次提交，按 created_at 区分，用于 GAME Data Capture 当日 option 过滤 + Submitted Processes 列表展示';

-- =============================================================================
-- Data Capture (tenant model — Games / Bank page, no JSON / no scope_*)
-- Reuses: process(+category), process_day, process_description(+link), process_submitted
-- Games option: category=GAME + process_day + NOT IN process_submitted
-- Bank option: category=BANK fixed codes; draft always TEXT only (data_capture_draft*)
-- Summary populate + Formula Maintenance: data_capture_formula (hard DELETE; not bound to one capture)
-- Final submit line snapshot: data_capture_line (bound to data_captures)
-- NOT planned: data_capture_submit_queue (PHP batch/post_max workaround; Spring one-shot submit)
-- NOT planned: data_capture_summary_state (UI draft → frontend session/localStorage; formula → data_capture_formula)
-- =============================================================================

CREATE TABLE `data_captures` (
    `id`                 INT UNSIGNED NOT NULL AUTO_INCREMENT,
    `tenant_id`          INT UNSIGNED NOT NULL COMMENT 'FK tenant.id',
    `category`           ENUM('GAME', 'BANK') NOT NULL COMMENT '与 process.category 对齐',
    `capture_date`       DATE NOT NULL COMMENT '业务捕获日期',
    `process_id`         INT UNSIGNED NOT NULL COMMENT 'FK process.id',
    `currency_id`        INT UNSIGNED NOT NULL COMMENT 'FK currency.id',
    `remark`             TEXT DEFAULT NULL COMMENT '表单 Remark（BANK 非必填）',
    `remove_word`        TEXT DEFAULT NULL COMMENT 'GAME：提交瞬间从 process 拷贝快照',
    `replace_word_from`  VARCHAR(255) DEFAULT NULL COMMENT 'GAME 快照',
    `replace_word_to`    VARCHAR(255) DEFAULT NULL COMMENT 'GAME 快照',
    `created_by`         VARCHAR(50) DEFAULT NULL COMMENT '提交人 login_id',
    `created_at`         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_dc_tenant_capture_date` (`tenant_id`, `capture_date`),
    KEY `idx_dc_tenant_process_date` (`tenant_id`, `process_id`, `capture_date`),
    KEY `idx_dc_category` (`tenant_id`, `category`),
    CONSTRAINT `fk_dc_tenant` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_dc_process` FOREIGN KEY (`process_id`) REFERENCES `process` (`id`),
    CONSTRAINT `fk_dc_currency` FOREIGN KEY (`currency_id`) REFERENCES `currency` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Data Capture 提交头（GAME/BANK）；description 多选见 data_capture_description';

CREATE TABLE `data_capture_description` (
    `id`             INT UNSIGNED NOT NULL AUTO_INCREMENT,
    `capture_id`     INT UNSIGNED NOT NULL COMMENT 'FK data_captures.id',
    `description_id` INT UNSIGNED NOT NULL COMMENT 'FK process_description.id（本次选中的多选快照）',
    `created_at`     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_dcd_capture_description` (`capture_id`, `description_id`),
    KEY `idx_dcd_description` (`description_id`),
    CONSTRAINT `fk_dcd_capture` FOREIGN KEY (`capture_id`) REFERENCES `data_captures` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_dcd_description` FOREIGN KEY (`description_id`) REFERENCES `process_description` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='GAME Capture 选中的 description 多选桥表（配置允许列表仍用 process_description_link）';

-- Summary 最终 Submit 行快照（替代 legacy data_capture_details；无 company_id / scope_*）
-- 绑定单次 data_captures；与 data_capture_formula（持久配置）分离
CREATE TABLE `data_capture_line` (
    `id`                    INT UNSIGNED NOT NULL AUTO_INCREMENT,
    `tenant_id`             INT UNSIGNED NOT NULL COMMENT 'FK tenant.id',
    `capture_id`            INT UNSIGNED NOT NULL COMMENT 'FK data_captures.id',
    `product_type`          ENUM('MAIN', 'SUB') NOT NULL DEFAULT 'MAIN' COMMENT 'Summary 主行 / 子行快照',
    `id_product`            VARCHAR(255) NOT NULL COMMENT '本行 Id Product',
    `id_product_main`       VARCHAR(255) DEFAULT NULL COMMENT '主 product（SUB 时为其父）',
    `id_product_sub`        VARCHAR(255) DEFAULT NULL COMMENT '子 product（MAIN 时为 NULL）',
    `description_main`      VARCHAR(255) DEFAULT NULL COMMENT '主行描述快照',
    `description_sub`       VARCHAR(255) DEFAULT NULL COMMENT '子行描述快照',
    `formula_variant`       TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '同 id_product 多套公式快照',
    `display_order`         INT DEFAULT NULL COMMENT 'Summary 行序（rowIndex）',
    `account_id`            INT UNSIGNED NOT NULL COMMENT 'FK account.id',
    `currency_id`           INT UNSIGNED NOT NULL COMMENT 'FK currency.id',
    `source_columns`        TEXT DEFAULT NULL COMMENT '公式引用列（旧 columns_value）',
    `source_value`          TEXT DEFAULT NULL COMMENT '提交时 source / formula 展示快照',
    `source_percent`        VARCHAR(255) NOT NULL DEFAULT '0',
    `enable_source_percent` TINYINT(1) NOT NULL DEFAULT 1,
    `formula`               TEXT DEFAULT NULL COMMENT '提交时公式快照',
    `processed_amount`      DECIMAL(25, 8) NOT NULL DEFAULT 0 COMMENT '最终入账金额（Customer Report / History）',
    `rate`                  DECIMAL(25, 8) DEFAULT NULL COMMENT '解析后的数值 rate',
    `rate_expression`       VARCHAR(64) DEFAULT NULL COMMENT '原始 rate 文本 e.g. *3 /3 3',
    `transaction_id`        INT UNSIGNED DEFAULT NULL COMMENT 'FK transactions.id — 本行生成的那条 WIN/LOSE 流水（一行至多一条；processed_amount=0 或本字段补加前提交的历史行为 NULL）',
    `created_at`            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_dcl_capture` (`capture_id`),
    KEY `idx_dcl_tenant_capture` (`tenant_id`, `capture_id`),
    KEY `idx_dcl_tenant_account` (`tenant_id`, `account_id`),
    KEY `idx_dcl_account` (`account_id`),
    KEY `idx_dcl_product` (`capture_id`, `id_product`, `account_id`, `formula_variant`),
    KEY `idx_dcl_transaction` (`transaction_id`),
    CONSTRAINT `fk_dcl_tenant`
        FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_dcl_capture`
        FOREIGN KEY (`capture_id`) REFERENCES `data_captures` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_dcl_account`
        FOREIGN KEY (`account_id`) REFERENCES `account` (`id`),
    CONSTRAINT `fk_dcl_currency`
        FOREIGN KEY (`currency_id`) REFERENCES `currency` (`id`),
    CONSTRAINT `fk_dcl_transaction`
        FOREIGN KEY (`transaction_id`) REFERENCES `transactions` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Data Capture Summary 最终 Submit 行快照（替代 legacy data_capture_details；无 scope_*）';

-- Capture Maintenance 软删归档表：删除单位是「整个 capture」（同一 capture_id 下所有行一起删），
-- 不是按单行选择删（业务约定：不会出现同一 capture 下部分行删、部分行保留的情况）。
-- 归档 + 从 data_capture_line 硬删；data_captures header 永不清理，即便旗下所有行都已归档。
CREATE TABLE `data_capture_line_deleted` (
    `id`                    INT UNSIGNED NOT NULL AUTO_INCREMENT,
    `line_id`               INT UNSIGNED NOT NULL COMMENT '原 data_capture_line.id',
    `tenant_id`             INT UNSIGNED NOT NULL COMMENT 'FK tenant.id',
    `capture_id`            INT UNSIGNED NOT NULL COMMENT 'FK data_captures.id（header 不删，仍可查到）',
    `product_type`          ENUM('MAIN', 'SUB') NOT NULL DEFAULT 'MAIN',
    `id_product`            VARCHAR(255) NOT NULL,
    `id_product_main`       VARCHAR(255) DEFAULT NULL,
    `id_product_sub`        VARCHAR(255) DEFAULT NULL,
    `description_main`      VARCHAR(255) DEFAULT NULL,
    `description_sub`       VARCHAR(255) DEFAULT NULL,
    `formula_variant`       TINYINT UNSIGNED NOT NULL DEFAULT 1,
    `display_order`         INT DEFAULT NULL,
    `account_id`            INT UNSIGNED NOT NULL,
    `currency_id`           INT UNSIGNED NOT NULL,
    `source_columns`        TEXT DEFAULT NULL,
    `source_value`          TEXT DEFAULT NULL,
    `source_percent`        VARCHAR(255) NOT NULL DEFAULT '0',
    `enable_source_percent` TINYINT(1) NOT NULL DEFAULT 1,
    `formula`               TEXT DEFAULT NULL,
    `processed_amount`      DECIMAL(25, 8) NOT NULL DEFAULT 0,
    `rate`                  DECIMAL(25, 8) DEFAULT NULL,
    `rate_expression`       VARCHAR(64) DEFAULT NULL,
    `transaction_id`        INT UNSIGNED DEFAULT NULL COMMENT '原关联的 transactions.id（该条流水已单独归档进 transactions_deleted / 硬删，这里仅留痕）',
    `created_at`            TIMESTAMP NULL DEFAULT NULL COMMENT '原 data_capture_line.created_at',
    `deleted_by`            VARCHAR(100) DEFAULT NULL COMMENT '删除人 login_id',
    `deleted_at`            TIMESTAMP NULL DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_dcld_line` (`line_id`),
    KEY `idx_dcld_tenant_capture` (`tenant_id`, `capture_id`),
    KEY `idx_dcld_deleted_at` (`deleted_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Archived soft-deleted data_capture_line rows (Capture Maintenance；按 capture 整体归档)';

-- Summary 列表 + Edit Formula Save + Formula Maintenance（替代 legacy data_capture_templates）
-- 配置与单次 capture 分离：不存 last_processed_amount / data_capture_id；Maintenance 为硬 DELETE
CREATE TABLE `data_capture_formula` (
    `id`                    INT UNSIGNED NOT NULL AUTO_INCREMENT,
    `tenant_id`             INT UNSIGNED NOT NULL COMMENT 'FK tenant.id',
    `process_id`            INT UNSIGNED NOT NULL COMMENT 'FK process.id',
    `product_type`          ENUM('MAIN', 'SUB') NOT NULL DEFAULT 'MAIN' COMMENT 'Summary 主行 / 子行',
    `id_product`            VARCHAR(255) NOT NULL COMMENT 'Summary Id Product（如 AAA）',
    `parent_id_product`     VARCHAR(255) DEFAULT NULL COMMENT 'SUB 时父 id_product；MAIN 为 NULL',
    `formula_variant`       TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '同 id_product 多套公式',
    `sub_order`             DECIMAL(11, 2) DEFAULT NULL COMMENT 'SUB 排序',
    `row_index`             INT DEFAULT NULL COMMENT '与 Capture 表格行索引对齐（可选）',
    `account_id`            INT UNSIGNED DEFAULT NULL COMMENT 'FK account.id',
    `currency_id`           INT UNSIGNED DEFAULT NULL COMMENT 'FK currency.id',
    `description`           VARCHAR(255) DEFAULT NULL COMMENT '行描述 / Edit Formula Description',
    `source_columns`        TEXT DEFAULT NULL COMMENT '公式引用的 Capture 列/格（如 $2,$3）',
    `columns_display`       TEXT DEFAULT NULL COMMENT 'Data 下拉展示文案',
    `formula`               TEXT DEFAULT NULL COMMENT '公式表达式（计算与展示唯一来源）',
    `input_method`          VARCHAR(100) DEFAULT NULL COMMENT 'Input Method（可选）',
    `source_percent`        VARCHAR(255) NOT NULL DEFAULT '0',
    `enable_source_percent` TINYINT(1) NOT NULL DEFAULT 1,
    `enable_input_method`   TINYINT(1) NOT NULL DEFAULT 0,
    `created_by`            VARCHAR(50) DEFAULT NULL COMMENT 'login_id',
    `updated_by`            VARCHAR(50) DEFAULT NULL COMMENT 'login_id',
    `created_at`            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_dcf_tenant_process_formula` (
        `tenant_id`,
        `process_id`,
        `product_type`,
        `id_product`,
        `parent_id_product`,
        `formula_variant`,
        `sub_order`,
        `account_id`
    ),
    KEY `idx_dcf_tenant_process` (`tenant_id`, `process_id`),
    KEY `idx_dcf_process_product` (`process_id`, `id_product`),
    KEY `idx_dcf_account` (`account_id`),
    CONSTRAINT `fk_dcf_tenant`
        FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_dcf_process`
        FOREIGN KEY (`process_id`) REFERENCES `process` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_dcf_account`
        FOREIGN KEY (`account_id`) REFERENCES `account` (`id`) ON DELETE SET NULL,
    CONSTRAINT `fk_dcf_currency`
        FOREIGN KEY (`currency_id`) REFERENCES `currency` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Data Capture Summary 持久公式 + Formula Maintenance；硬删除；不绑定单次 data_captures';

CREATE TABLE `data_capture_draft` (
    `id`            INT UNSIGNED NOT NULL AUTO_INCREMENT,
    `tenant_id`     INT UNSIGNED NOT NULL COMMENT 'FK tenant.id（company/group ledger 均用 tenant）',
    `process_id`    INT UNSIGNED NOT NULL COMMENT 'FK process.id（BANK 四码之一）',
    `currency_id`   INT UNSIGNED NOT NULL COMMENT 'FK currency.id',
    `updated_at`    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `created_at`    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_draft_tenant_process_currency` (`tenant_id`, `process_id`, `currency_id`),
    KEY `idx_draft_tenant` (`tenant_id`),
    CONSTRAINT `fk_draft_tenant` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_draft_process` FOREIGN KEY (`process_id`) REFERENCES `process` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_draft_currency` FOREIGN KEY (`currency_id`) REFERENCES `currency` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='BANK Data Capture 表格草稿头（仅 TEXT）';

CREATE TABLE `data_capture_draft_cell` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `draft_id`    INT UNSIGNED NOT NULL COMMENT 'FK data_capture_draft.id',
    `row_index`   SMALLINT UNSIGNED NOT NULL COMMENT '0-based（A=0）',
    `col_index`   SMALLINT UNSIGNED NOT NULL COMMENT '1-based（与 UI 列号一致）',
    `cell_value`  TEXT NOT NULL COMMENT '纯文本；空单元格不落库',
    `updated_at`  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_draft_cell_pos` (`draft_id`, `row_index`, `col_index`),
    KEY `idx_draft_cell_draft` (`draft_id`),
    CONSTRAINT `fk_draft_cell_draft` FOREIGN KEY (`draft_id`) REFERENCES `data_capture_draft` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='BANK Data Capture 草稿单元格（无 JSON）';

-- =============================================================================
-- Bank Process (tenant model — list/CRUD + Accounting Due / Resend schema)
-- Reuses: tenant, account, account_tenant_access, currency, account_currency
-- Due tables: bank_process_accounting_posted, bank_process_resend_daily_guard
-- Post writes: transactions (N lines) → bank_process_accounting_posted (1 ledger row)
-- Open Resend (one make-up bill): bank_process.resend_schedule_*
-- Same-day Post lock: bank_process_resend_daily_guard (cleared on Maintenance txn delete)
-- =============================================================================

CREATE TABLE `bank_country` (
    `id`         INT UNSIGNED NOT NULL AUTO_INCREMENT,
    `tenant_id`  INT UNSIGNED NOT NULL COMMENT 'FK tenant.id',
    `code`       VARCHAR(50)  NOT NULL COMMENT 'MYR, SGD, AUD ...',
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_bank_country_tenant_code` (`tenant_id`, `code`),
    CONSTRAINT `fk_bank_country_tenant`
        FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Tenant country options for Bank Process dropdown';

CREATE TABLE `bank_option` (
   `id`         INT UNSIGNED NOT NULL AUTO_INCREMENT,
   `tenant_id`  INT UNSIGNED NOT NULL COMMENT 'FK tenant.id',
   `country_id` INT UNSIGNED NOT NULL COMMENT 'FK bank_country.id',
   `name`       VARCHAR(200) NOT NULL COMMENT 'UBANK, RHB, CIMB ...',
   `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
   PRIMARY KEY (`id`),
   UNIQUE KEY `uk_bank_option_country_name` (`country_id`, `name`),
   KEY `idx_bank_option_tenant` (`tenant_id`),
   CONSTRAINT `fk_bank_option_tenant`
       FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`) ON DELETE CASCADE,
   CONSTRAINT `fk_bank_option_country`
       FOREIGN KEY (`country_id`) REFERENCES `bank_country` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Banks under a country; cascade when country deleted';

CREATE TABLE `bank_process` (
    `id`                   INT UNSIGNED NOT NULL AUTO_INCREMENT,
    `tenant_id`            INT UNSIGNED NOT NULL COMMENT 'FK tenant.id',

    `country_id`           INT UNSIGNED NOT NULL COMMENT 'FK bank_country.id',
    `bank_option_id`       INT UNSIGNED NOT NULL COMMENT 'FK bank_option.id',
    `card_owner`           VARCHAR(255) NOT NULL COMMENT 'Card Owner text',
    `card_owner_type`      VARCHAR(100) NOT NULL COMMENT 'Type e.g. BUSINESS',
    `day_start`            DATE                  DEFAULT NULL,
    `day_end`              DATE                  DEFAULT NULL COMMENT 'Optional; UI may derive from day_start+contract',
    `day_end_monthly_cap_enabled` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '1st of every month only: 1=last month DAY_END_TAIL to day_end; 0=last month FULL_MONTH to month end',
    `expired_at_creation`  TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'FIRST_OF_EVERY_MONTH/MONTHLY only: set once at insert; 1=day_end''s month was already before the creation month, so ACTIVE never extends billing past day_end',
    `frequency`            ENUM( 'FIRST_OF_EVERY_MONTH', 'MONTHLY', 'ONCE', 'DAY', 'WEEK') NOT NULL DEFAULT 'FIRST_OF_EVERY_MONTH',

    `supplier_account_id`  INT UNSIGNED          DEFAULT NULL COMMENT 'FK account.id — Supplier',
    `supplier_price`       DECIMAL(25, 8)        DEFAULT NULL COMMENT 'Supplier price (list Cost / Buy Price)',
    `customer_account_id`  INT UNSIGNED          DEFAULT NULL COMMENT 'FK account.id — Customer',
    `customer_price`       DECIMAL(25, 8)        DEFAULT NULL COMMENT 'Customer price (list Price / Sell Price)',
    `company_account_id`   INT UNSIGNED          DEFAULT NULL COMMENT 'FK account.id — Company',
    `company_price`        DECIMAL(25, 8)        DEFAULT NULL COMMENT 'Company price (list Profit)',

    `contract`             VARCHAR(20)           DEFAULT NULL COMMENT '1 / 3 / 6 months',
    `insurance_price`      DECIMAL(25, 8)        DEFAULT NULL COMMENT 'Insurance amount with contract',
    `sop`                  TEXT                  DEFAULT NULL,
    `remark`               VARCHAR(500)          DEFAULT NULL,
    `status`               ENUM('WAITING', 'ACTIVE', 'OFFICIAL', 'E_INVOICE', 'INACTIVE', 'BLOCK' ) NOT NULL DEFAULT 'ACTIVE' COMMENT 'WAITING=before day_start (also derivable); ACTIVE/OFFICIAL/E_INVOICE=contract ongoing; INACTIVE/BLOCK=stopped',

    -- Open Resend make-up bill (parallel to normal Due; never overrides contract day_start/end/frequency).
    -- At most one open make-up per process: new Resend overwrites these three columns.
    -- Inbox adds one RESEND_CONSOLIDATED row from this schedule; Post/Skip clears it.
    -- Duplicate reject: same day_start + same frequency while still open.
    `resend_schedule_day_start`  DATE DEFAULT NULL COMMENT 'Open make-up billing_start / posted anchor; NULL = no open Resend',
    `resend_schedule_day_end`    DATE DEFAULT NULL COMMENT 'Open make-up billing_end (computed at Resend by frequency)',
    `resend_schedule_frequency`  ENUM('FIRST_OF_EVERY_MONTH', 'MONTHLY', 'ONCE', 'DAY', 'WEEK') DEFAULT NULL COMMENT 'Frequency chosen in Resend modal',

    `created_by`           VARCHAR(50)           DEFAULT NULL,
    `updated_by`           VARCHAR(50)           DEFAULT NULL,
    `created_at`           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (`id`),
    KEY `idx_bp_tenant` (`tenant_id`),
    KEY `idx_bp_tenant_status` (`tenant_id`, `status`),
    KEY `idx_bp_tenant_day_start` (`tenant_id`, `day_start`),
    KEY `idx_bp_country` (`country_id`),
    KEY `idx_bp_bank_option` (`bank_option_id`),
    KEY `idx_bp_supplier` (`supplier_account_id`),
    KEY `idx_bp_customer` (`customer_account_id`),

    CONSTRAINT `fk_bp_tenant`
        FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_bp_country`
        FOREIGN KEY (`country_id`) REFERENCES `bank_country` (`id`),
    CONSTRAINT `fk_bp_bank_option`
        FOREIGN KEY (`bank_option_id`) REFERENCES `bank_option` (`id`),
    CONSTRAINT `fk_bp_supplier`
        FOREIGN KEY (`supplier_account_id`) REFERENCES `account` (`id`) ON DELETE SET NULL,
    CONSTRAINT `fk_bp_customer`
        FOREIGN KEY (`customer_account_id`) REFERENCES `account` (`id`) ON DELETE SET NULL,
    CONSTRAINT `fk_bp_company_account`
        FOREIGN KEY (`company_account_id`) REFERENCES `account` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Bank Process deal row — list + add/update + open Resend schedule';

CREATE TABLE `bank_process_share` (
  `id`              INT UNSIGNED NOT NULL AUTO_INCREMENT,
  `bank_process_id` INT UNSIGNED NOT NULL COMMENT 'FK bank_process.id',
  `account_id`      INT UNSIGNED NOT NULL COMMENT 'FK account.id',
  `amount`          DECIMAL(25, 8) NOT NULL DEFAULT 0,
  `sort_order`      INT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_bps_process` (`bank_process_id`),
  CONSTRAINT `fk_bps_process`
      FOREIGN KEY (`bank_process_id`) REFERENCES `bank_process` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_bps_account`
      FOREIGN KEY (`account_id`) REFERENCES `account` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Profit sharing lines (replaces profit_sharing TEXT)';

-- Accounting Due ledger: which period was posted / skipped.
-- One posted row can own many transactions via transactions.bank_process_posted_id (no single transaction_id).
CREATE TABLE `bank_process_accounting_posted` (
  `id`              INT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`       INT UNSIGNED NOT NULL COMMENT 'FK tenant.id',
  `bank_process_id` INT UNSIGNED NOT NULL COMMENT 'FK bank_process.id',
  `posted_date`     DATE NOT NULL COMMENT 'Due anchor date (billing due day)',
  `period_type`     ENUM('MONTHLY', 'FIRST_MONTH', 'PARTIAL_FIRST_MONTH', 'FULL_MONTH', 'DAY_END_TAIL', 'ONCE_ONE_OFF', 'COMPENSATION', 'RESEND_CONSOLIDATED', 'WEEKLY','DAILY', 'DAILY_CONSOLIDATED') NOT NULL DEFAULT 'MONTHLY',
  `outcome`         ENUM('POSTED', 'SKIPPED') NOT NULL DEFAULT 'POSTED' COMMENT 'Replaces old period_type *_skipped suffix',
  `billing_start`   DATE DEFAULT NULL COMMENT 'Optional period start for display / clear',
  `billing_end`     DATE DEFAULT NULL COMMENT 'Optional period end for display / clear',
  `created_at`      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `created_by`      VARCHAR(50) DEFAULT NULL COMMENT 'Actor login_id (admin=user.login_id; owner=owner_code)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_bpap` (`tenant_id`, `bank_process_id`, `posted_date`, `period_type`),
  KEY `idx_bpap_tenant_date` (`tenant_id`, `posted_date`),
  KEY `idx_bpap_process` (`bank_process_id`),
  KEY `idx_bpap_tenant_process` (`tenant_id`, `bank_process_id`),
  CONSTRAINT `fk_bpap_tenant`
      FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_bpap_bank_process`
      FOREIGN KEY (`bank_process_id`) REFERENCES `bank_process` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Bank Process Accounting Due ledger: posted / skipped periods';

-- Same-day Resend lock after Post to Transaction (keyed by day_start only — frequency ignored).
-- Written on Post success; cleared when Maintenance deletes that bank-process txn (or prune stale).
-- Next calendar day: guard_date mismatch → lock gone. Other day_starts remain Resend-able today.
CREATE TABLE `bank_process_resend_daily_guard` (
  `id`               INT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`        INT UNSIGNED NOT NULL COMMENT 'FK tenant.id',
  `bank_process_id`  INT UNSIGNED NOT NULL COMMENT 'FK bank_process.id',
  `resend_day_start` DATE NOT NULL COMMENT 'Posted make-up anchor day_start (freq-agnostic same-day lock)',
  `guard_date`       DATE NOT NULL COMMENT 'Lock calendar day (app-local date, usually today)',
  `created_at`       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  -- guard_date before resend_day_start: covers lock check and list-today prune without a second index
  UNIQUE KEY `uk_bprdg` (`tenant_id`, `bank_process_id`, `guard_date`, `resend_day_start`),
  CONSTRAINT `fk_bprdg_tenant`
      FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_bprdg_bank_process`
      FOREIGN KEY (`bank_process_id`) REFERENCES `bank_process` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Same-day Resend lock per process+day_start after Post; Maintenance txn delete clears';

-- Tenant transaction lines (replaces legacy PHP transactions).
-- One row = one account amount. Bank Process Post: N lines share one bank_process_posted_id.
-- Manual Payment / History remark uses remark (not legacy sms).
-- RATE: two rows (leg1 + leg2) share rate_group_id; Cr/Dr = To− / From+ (same as PAYMENT).
-- Legacy PHP RATE ledgers transactions_rate_details / transaction_entry are NOT in this schema.
CREATE TABLE `transactions` (
    `id`                     INT UNSIGNED NOT NULL AUTO_INCREMENT,
    `tenant_id`              INT UNSIGNED NOT NULL COMMENT 'FK tenant.id',

    `transaction_type`       ENUM('WIN', 'LOSE', 'PAYMENT', 'CONTRA','CLAIM', 'RATE', 'CLEAR', 'ADJUSTMENT', 'PROFIT') NOT NULL,
    `account_id`             INT UNSIGNED NOT NULL COMMENT 'FK account.id (To / payer → −amount for transfer-style)',
    `from_account_id`        INT UNSIGNED DEFAULT NULL COMMENT 'FK account.id (From / receiver → +amount); transfer-style only',
    `currency_id`            INT UNSIGNED DEFAULT NULL COMMENT 'FK currency.id (currency.tenant_id = tenant_id)',

    `amount`                 DECIMAL(25, 8) NOT NULL COMMENT 'ADJUSTMENT may be negative non-zero; other types >= 0',
    `transaction_date`       DATE NOT NULL COMMENT 'Economic / capture date for list filters',
    `description`            VARCHAR(500) DEFAULT NULL COMMENT 'System / process line description',
    `remark`                 VARCHAR(500) DEFAULT NULL COMMENT 'User / system remark (Payment History Remark)',

    `created_by`             VARCHAR(50) DEFAULT NULL COMMENT 'Creator login_id (admin=user.login_id; owner=owner_code)',
    `updated_by`             VARCHAR(50) DEFAULT NULL COMMENT 'Last updater login_id (same convention)',

    `approval_status`        ENUM('APPROVED', 'PENDING') NOT NULL DEFAULT 'APPROVED',
    `approved_by`            VARCHAR(50) DEFAULT NULL COMMENT 'Approver login_id (same convention as created_by)',
    `approved_at`            TIMESTAMP NULL DEFAULT NULL,

    `bank_process_posted_id` INT UNSIGNED DEFAULT NULL COMMENT 'FK bank_process_accounting_posted.id; NULL = manual / non-BP txn',

    `rate_group_id`          VARCHAR(50) DEFAULT NULL COMMENT 'RATE only: shared by leg1+leg2; NULL for other types',

    `created_at`             TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`             TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (`id`),
    KEY `idx_txn_tenant_date` (`tenant_id`, `transaction_date`),
    KEY `idx_txn_tenant_account_date` (`tenant_id`, `account_id`, `transaction_date`),
    KEY `idx_txn_posted` (`bank_process_posted_id`),
    KEY `idx_txn_approval` (`tenant_id`, `approval_status`),
    KEY `idx_txn_currency` (`currency_id`),
    KEY `idx_txn_tenant_rate_group` (`tenant_id`, `rate_group_id`),

    CONSTRAINT `fk_txn_tenant`
        FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_txn_account`
        FOREIGN KEY (`account_id`) REFERENCES `account` (`id`),
    CONSTRAINT `fk_txn_from_account`
        FOREIGN KEY (`from_account_id`) REFERENCES `account` (`id`) ON DELETE SET NULL,
    CONSTRAINT `fk_txn_currency`
        FOREIGN KEY (`currency_id`) REFERENCES `currency` (`id`) ON DELETE SET NULL,
    CONSTRAINT `fk_txn_bp_posted`
        FOREIGN KEY (`bank_process_posted_id`) REFERENCES `bank_process_accounting_posted` (`id`)
            ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Tenant transaction lines; audit via login_id; BP Post via bank_process_posted_id';

-- RATE group header (1 row per submit). Ledger = transactions legs; this row = FX metadata + links.
-- Example: MYR 1000 @ 1.7 → CNY 1700 → two transactions + one transactions_rate.
-- Middle-Man: Rate-Mul commission (divide/multiply modes) and/or Service Fee, minus optional
-- Platform Fee, net into one Win/Loss transactions row each (rate portion / fee portion);
-- Platform Fee itself only reduces the fee portion, no separate ledger row.
-- Not in schema: legacy transactions_rate_details / transaction_entry.
CREATE TABLE `transactions_rate` (
    `id`                   INT UNSIGNED NOT NULL AUTO_INCREMENT,
    `tenant_id`            INT UNSIGNED NOT NULL COMMENT 'FK tenant.id',
    `rate_group_id`        VARCHAR(50)  NOT NULL COMMENT 'Shared with transactions.rate_group_id',

    `leg1_transaction_id`  INT UNSIGNED NOT NULL COMMENT 'First currency leg FK transactions.id (e.g. MYR)',
    `leg2_transaction_id`  INT UNSIGNED NOT NULL COMMENT 'Second currency leg FK transactions.id (e.g. CNY)',

    `exchange_rate`        DECIMAL(18, 8) NOT NULL COMMENT 'Effective multiplier (amount_to/amount_from); /1.7 stored as 1/1.7',
    `rate_expression`      VARCHAR(64) DEFAULT NULL COMMENT 'UI raw e.g. 1.7 or /1.7',
    `currency_from_id`     INT UNSIGNED NOT NULL COMMENT 'Leg1 currency FK currency.id',
    `amount_from`          DECIMAL(25, 8) NOT NULL COMMENT 'Leg1 amount > 0',
    `currency_to_id`       INT UNSIGNED NOT NULL COMMENT 'Leg2 currency FK currency.id',
    `amount_to`            DECIMAL(25, 8) NOT NULL COMMENT 'Leg2 amount > 0',

    `middleman_account_id` INT UNSIGNED DEFAULT NULL COMMENT 'FK account.id; Middle-Man fee account',
    `middleman_rate`       DECIMAL(18, 8) DEFAULT NULL COMMENT 'Middle-Man multiplier (paired with account); divide mode stores the divisor',
    `middleman_rate_expression` VARCHAR(32) DEFAULT NULL COMMENT 'Raw Rate-Mul input, e.g. /1.55 (divide) or 2.93 (multiply/new-rate)',
    `middleman_amount`     DECIMAL(25, 8) DEFAULT NULL COMMENT 'Service Fee face value in currency_to (leg2); no FX conversion',
    `platform_fee_amount`  DECIMAL(25, 8) DEFAULT NULL COMMENT 'Platform Fee face value in currency_to (leg2); reduces middleman Win/Loss (Fee - PT), no separate ledger row',

    `created_at`           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_rate_group` (`tenant_id`, `rate_group_id`),
    KEY `idx_rate_leg1` (`leg1_transaction_id`),
    KEY `idx_rate_leg2` (`leg2_transaction_id`),

    CONSTRAINT `fk_rate_tenant`
        FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_rate_leg1_txn`
        FOREIGN KEY (`leg1_transaction_id`) REFERENCES `transactions` (`id`),
    CONSTRAINT `fk_rate_leg2_txn`
        FOREIGN KEY (`leg2_transaction_id`) REFERENCES `transactions` (`id`),
    CONSTRAINT `fk_rate_ccy_from`
        FOREIGN KEY (`currency_from_id`) REFERENCES `currency` (`id`),
    CONSTRAINT `fk_rate_ccy_to`
        FOREIGN KEY (`currency_to_id`) REFERENCES `currency` (`id`),
    CONSTRAINT `fk_rate_middleman_account`
        FOREIGN KEY (`middleman_account_id`) REFERENCES `account` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='RATE group header: exchange_rate + leg txn links; Cr/Dr from transactions only';

DROP TABLE IF EXISTS `transactions_deleted`;

CREATE TABLE `transactions_deleted` (
    `id`                       int(11) NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `tenant_id`                int(11) NOT NULL COMMENT 'Tenant (replaces company_id)',
    `transaction_id`           int(11) NOT NULL COMMENT 'Original transactions.id',
    `transaction_type`         enum('WIN', 'LOSE', 'PAYMENT', 'CONTRA', 'CLAIM', 'RATE', 'CLEAR', 'ADJUSTMENT', 'PROFIT') NOT NULL,
    `account_id`               int(11) NOT NULL COMMENT 'To account',
    `from_account_id`          int(11) DEFAULT NULL COMMENT 'From account',
    `currency_id`              int(11) DEFAULT NULL,
    `amount`                   decimal(25, 8) NOT NULL,
    `transaction_date`         date NOT NULL,
    `description`              varchar(500) DEFAULT NULL,
    `remark`                   varchar(500) DEFAULT NULL COMMENT 'Replaces sms',
    `created_by`               varchar(100) DEFAULT NULL COMMENT 'Submitter login_id',
    `created_at`               timestamp NULL DEFAULT NULL,
    `deleted_by`               varchar(100) DEFAULT NULL COMMENT 'Deleter login_id',
    `deleted_at`               timestamp NULL DEFAULT NULL,
    `bank_process_posted_id`   int(11) DEFAULT NULL COMMENT 'NULL = Payment Maintenance; set for BP Maintenance',
    `rate_group_id`            varchar(50) DEFAULT NULL COMMENT 'RATE group when applicable',
    INDEX `idx_tenant_date` (`tenant_id`, `transaction_date`),
    INDEX `idx_transaction_id` (`transaction_id`),
    INDEX `idx_deleted_at` (`deleted_at`),
    INDEX `idx_bp_posted` (`tenant_id`, `bank_process_posted_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Archived soft-deleted transactions (Payment + Bank Process Maintenance)';

SET FOREIGN_KEY_CHECKS = 1;
