-- =============================================================================
-- Tenant-model login DB schema (testcount).
-- Apply AFTER backend/src/main/resources/schema.sql on dev DB, or standalone
-- when bootstrapping the login module only.
DROP TABLE IF EXISTS `submitted_processes`;
DROP TABLE IF EXISTS `process`;
DROP TABLE IF EXISTS `description`;
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
  UNIQUE KEY `uk_account_account_id` (`account_id`),
  KEY `idx_account_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Member identity';

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
  `tenand_id`   INT UNSIGNED NOT NULL COMMENT 'FK tenant.id',
  `code`       VARCHAR(10)  NOT NULL COMMENT '6-digit verification code',
  `expires_at` DATETIME     NOT NULL COMMENT 'Code expiry (typically 15 minutes)',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`email`, `owner_id`),
  KEY `idx_prto_owner_id` (`owner_id`),
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
  `prefix`       VARCHAR(100) NOT NULL COMMENT 'Marquee label shown before content',
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

CREATE TABLE `tenant_auto_renew_request` (
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
    `saved_by`         INT UNSIGNED DEFAULT NULL COMMENT '保存快照的操作人(关联 user.id)',
    `saved_at`         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP(),
    UNIQUE KEY `uq_tenant_oh_month_account` (`tenant_id`, `effective_month`, `account_id`, `owner_type`, `partner_tenant_id`),
    CONSTRAINT `fk_toh_tenant` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_toh_partner_tenant` FOREIGN KEY (`partner_tenant_id`) REFERENCES `tenant` (`id`) ON DELETE SET NULL,
    CONSTRAINT `fk_toh_saved_by` FOREIGN KEY (`saved_by`) REFERENCES `user` (`id`) ON DELETE SET NULL,
    KEY `idx_toh_tenant_month` (`tenant_id`, `effective_month`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户股权历史月度快照表';

-- =============================================================================
-- Core Process Tables (Optimized Tenant-Model)
-- =============================================================================

CREATE TABLE `process` (
  `id`              INT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`       INT UNSIGNED NOT NULL COMMENT 'FK tenant.id',
  `code`            VARCHAR(50) NOT NULL COMMENT '业务名称',
  `currency_id`     INT UNSIGNED NOT NULL COMMENT '默认币别 FK currency.id',
  `description_ids` JSON DEFAULT NULL COMMENT '关联的交易描述 ID 列表，例如 [12, 15]',
  `schedule_days`   JSON DEFAULT NULL COMMENT '运行的星期几，例如 [1, 2, 3, 4, 5, 6, 7]',
  `settings`        JSON DEFAULT NULL COMMENT '动态规则配置，如过滤词、替换对照表等',
  `remark`          TEXT DEFAULT NULL COMMENT '备注',
  `status`          ENUM('ACTIVE', 'INACTIVE') NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE=启用, INACTIVE=停用',
  `created_by`      INT UNSIGNED DEFAULT NULL COMMENT '创建人 FK user.id',
  `updated_by`      INT UNSIGNED DEFAULT NULL COMMENT '修改人 FK user.id',
  `created_at`      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_process_tenant_code` (`tenant_id`, `code`),
  CONSTRAINT `fk_process_tenant` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_process_currency` FOREIGN KEY (`currency_id`) REFERENCES `currency` (`id`),
  CONSTRAINT `fk_process_created_by` FOREIGN KEY (`created_by`) REFERENCES `user` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_process_updated_by` FOREIGN KEY (`updated_by`) REFERENCES `user` (`id`) ON DELETE SET NULL,
  KEY `idx_process_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='流程配置表';

CREATE TABLE `submitted_processes` (
  `id`             INT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tenant_id`      INT UNSIGNED NOT NULL COMMENT 'FK tenant.id',
  `process_id`     INT UNSIGNED NOT NULL COMMENT 'FK process.id',
  `user_id`        INT UNSIGNED NOT NULL COMMENT '操作人 FK user.id',
  `capture_date`   DATE NOT NULL COMMENT '业务捕获日期',
  `created_at`     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_submitted_tenant_process_date` (`tenant_id`, `process_id`, `capture_date`),
  CONSTRAINT `fk_sp_tenant` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_sp_process` FOREIGN KEY (`process_id`) REFERENCES `process` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_sp_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`),
  KEY `idx_sp_tenant_capture_date` (`tenant_id`, `capture_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='已提交流程记录表';

-- =============================================================================
-- Description Template Table (Optimized Tenant-Model)
-- =============================================================================

CREATE TABLE `process_description` (
  `id`        INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `tenant_id` INT UNSIGNED NOT NULL COMMENT 'FK tenant.id',
  `name`      VARCHAR(255) NOT NULL COMMENT '描述名称/模板内容',
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT `fk_process_description_tenant` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`) ON DELETE CASCADE,
  KEY `idx_description_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='交易描述库/模板表';