DROP TABLE IF EXISTS `account`;

CREATE TABLE `account` (
   `id`                  int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,
   `account_id`          varchar(255) NOT NULL,
   `name`                varchar(255) NOT NULL,
   `status`              enum('active','inactive') NOT NULL,
   `created_source`      varchar(50)    DEFAULT NULL COMMENT 'Account source, e.g. domain_auto/manual',
   `last_login`          datetime       DEFAULT NULL,
   `role`                varchar(50)  NOT NULL,
   `password`            varchar(255) NOT NULL,
   `payment_alert`       tinyint(1) DEFAULT 0,
   `alert_day`           varchar(255)   DEFAULT NULL COMMENT 'Alert type: weekly, monthly, or number 1-31',
   `alert_specific_date` date           DEFAULT NULL COMMENT 'Alert start date (YYYY-MM-DD)',
   `alert_amount`        decimal(25, 8) DEFAULT NULL,
   `remark`              text           DEFAULT NULL
);

DROP TABLE IF EXISTS `account_company`;

CREATE TABLE `account_company` (
   `id`         int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,
   `account_id` int(255) NOT NULL COMMENT 'Account ID',
   `company_id` int(255) UNSIGNED NOT NULL COMMENT 'Company ID',
   `scope_type` enum('company','group') NOT NULL DEFAULT 'company' COMMENT 'Tenant scope: company or group ledger',
   `scope_id`   bigint UNSIGNED DEFAULT NULL COMMENT 'Numeric scope: company.id or groups.id',
   `created_at` timestamp NOT NULL DEFAULT current_timestamp() COMMENT 'Created Time',
   `updated_at` timestamp NULL DEFAULT current_timestamp() ON UPDATE current_timestamp() COMMENT 'Updated Time'
);

DROP TABLE IF EXISTS `account_currency`;

CREATE TABLE `account_currency` (
    `id`          int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `account_id`  int(255) NOT NULL COMMENT 'Account ID',
    `currency_id` int(255) NOT NULL COMMENT 'Currency ID',
    `created_at`  timestamp NULL DEFAULT current_timestamp() COMMENT 'Created Time',
    `updated_at`  timestamp NULL DEFAULT current_timestamp() ON UPDATE current_timestamp() COMMENT 'Updated Time'
);

DROP TABLE IF EXISTS `account_currency_display_order`;

CREATE TABLE `account_currency_display_order`(
    `id`             int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `account_id`     int(255) NOT NULL COMMENT 'Account ID(Connect account.id)',
    `currency_order` text DEFAULT NULL COMMENT 'Currency code Desc,JSON Add ["JPY","MYR"]',
    `updated_at`     timestamp NULL DEFAULT current_timestamp() ON UPDATE current_timestamp() COMMENT 'Updated Time'
);

DROP TABLE IF EXISTS `account_link`;

CREATE TABLE `account_link` (
    `id`                int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `account_id_1`      int(11) NOT NULL COMMENT 'Acc 1 ID(Small ID)',
    `account_id_2`      int(11) NOT NULL COMMENT 'Acc 2 ID(Big ID)',
    `company_id`        int(10) UNSIGNED NOT NULL COMMENT 'Comp ID(Restrict in one)',
    `link_type`         enum('bidirectional','unidirectional') NOT NULL DEFAULT 'bidirectional' COMMENT 'Link type: bidirectional=Both, unidirectional=One-way',
    `source_account_id` int(11) DEFAULT NULL COMMENT 'unidirectional connect new Acc Id, bidirectional connect be null',
    `created_at`        timestamp NOT NULL DEFAULT current_timestamp() COMMENT 'Created Time',
    `updated_at`        timestamp NULL DEFAULT current_timestamp() ON UPDATE current_timestamp() COMMENT 'Updated Time'
);

DROP TABLE IF EXISTS `announcements`;

CREATE TABLE `announcements` (
     `id`           int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,
     `title`        varchar(500) NOT NULL COMMENT 'Announce Title',
     `content`      text         NOT NULL COMMENT 'Announce Content',
     `company_code` varchar(50)  NOT NULL DEFAULT 'C168' COMMENT 'Company Code,Just C168 Can View',
     `status`       enum('active','inactive') NOT NULL DEFAULT 'active' COMMENT 'Announce Status',
     `created_by`   int(11) NOT NULL COMMENT 'Created Acc ID',
     `user_type`    enum('user','owner') NOT NULL DEFAULT 'user',
     `created_at`   timestamp NULL DEFAULT current_timestamp() COMMENT 'Created Time',
     `updated_at`   timestamp NULL DEFAULT current_timestamp() ON UPDATE current_timestamp() COMMENT 'Updated Time'
);

DROP TABLE IF EXISTS `auto_login_credentials`;

CREATE TABLE `auto_login_credentials` (
      `id`                   int(11) NOT NULL,
      `company_id`           int(10) UNSIGNED NOT NULL COMMENT '公司ID（关联company表）',
      `name`                 varchar(255) NOT NULL COMMENT '凭证名称/描述',
      `website_url`          varchar(500) NOT NULL COMMENT '网站URL',
      `username`             varchar(255) NOT NULL COMMENT '用户名',
      `encrypted_password`   text         NOT NULL COMMENT '加密后的密码',
      `encryption_key`       varchar(64)  NOT NULL COMMENT '加密密钥（用于存储密钥标识）',
      `has_2fa`              tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否启用二重认证：0=否，1=是',
      `encrypted_2fa_code`   text                  DEFAULT NULL COMMENT '加密后的认证码（静态认证码或TOTP密钥）',
      `two_fa_type`          enum('static','totp','sms','email') DEFAULT NULL COMMENT '认证码类型：static=静态码，totp=时间基础一次性密码，sms=短信，email=邮箱',
      `two_fa_instructions`  text                  DEFAULT NULL COMMENT '认证码获取说明/提示',
      `auto_import_enabled`  tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否启用自动导入：0=否，1=是',
      `report_page_url`      varchar(500)          DEFAULT NULL COMMENT '报告页面URL（如果与登录URL不同，用于网页抓取模式）',
      `import_process_id`    int(11) DEFAULT NULL COMMENT '导入流程ID（关联process表）',
      `import_capture_date`  varchar(50)           DEFAULT NULL COMMENT '导入日期规则：today=今天，yesterday=昨天，或具体日期格式如Y-m-d',
      `import_currency_id`   int(11) DEFAULT NULL COMMENT '导入默认币别ID（关联currency表）',
      `import_field_mapping` text                  DEFAULT NULL COMMENT '导入字段映射配置（JSON格式）',
      `status`               enum('active','inactive') DEFAULT 'active' COMMENT '状态：active=启用，inactive=停用',
      `remark`               text                  DEFAULT NULL COMMENT '备注',
      `last_executed`        datetime              DEFAULT NULL COMMENT '最后执行时间',
      `last_result`          text                  DEFAULT NULL COMMENT '最后执行结果',
      `created_at`           datetime     NOT NULL DEFAULT current_timestamp() COMMENT '创建时间',
      `updated_at`           datetime     NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp() COMMENT '更新时间',
      `created_by`           int(11) DEFAULT NULL COMMENT '创建人ID（关联user表）'
);

DROP TABLE IF EXISTS `company`;

CREATE TABLE company (
   `id`                      int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,
   `company_id`              varchar(50) NOT NULL COMMENT 'External/business identifier for the company',
   `owner_id`                int(10) UNSIGNED DEFAULT NULL COMMENT 'FK to owner.id; NULL = detached from domain, ledger retained',
   `created_by`              varchar(50)          DEFAULT NULL,
   `created_at`              timestamp   NOT NULL DEFAULT current_timestamp(),
   `expiration_date`         date                 DEFAULT NULL COMMENT 'Company expiration date',
   `permissions`             longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'Company permissions, not inherited from parent groups, e.g. ["Gambling", "Bank", "Loan", "Rate", "Money"]' CHECK (json_valid(`permissions`)),
   `fee_share_allocations`   longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'Sales/CS/IT fee share % by account' CHECK (json_valid(`fee_share_allocations`)),
   `group_id`                varchar(50)          DEFAULT NULL COMMENT 'Parent group code for subsidiary companies only (not group definition)',
   `auto_renew_enabled`      tinyint(1) NOT NULL  DEFAULT 0 COMMENT 'Whether subscription auto-renew is enabled',
   `auto_renew_period`       varchar(20)          DEFAULT NULL COMMENT '7days|1month|3months|6months|1year',
   `payment_customer_id`     varchar(255)         DEFAULT NULL,
   `payment_subscription_id` varchar(255)         DEFAULT NULL,
   `auto_renew_updated_at`   datetime             DEFAULT NULL COMMENT 'Last auto-renew update time',
   `auto_renew_updated_by`   varchar(50)          DEFAULT NULL COMMENT 'Last auto-renew update by'
);

DROP TABLE IF EXISTS `company_countries`;

CREATE TABLE `company_countries` (
     `id`                  int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,
     `company_id` int(10) UNSIGNED NOT NULL,
     `country`    varchar(100) NOT NULL,
     `created_at` datetime     NOT NULL DEFAULT current_timestamp()
);

DROP TABLE IF EXISTS `company_ownership`;

CREATE TABLE `company_ownership` (
     `id`               int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,
     `company_id`       int(11) NOT NULL COMMENT 'FK company.id',
     `entity_type`      varchar(50)            DEFAULT 'account',
     `account_id`       int(11) NOT NULL COMMENT 'FK account.id',
     `group_id`         varchar(50)            DEFAULT NULL,
     `owner_type`       enum('account','owner','user','group') NOT NULL DEFAULT 'account',
     `percentage`       decimal(5, 2) NOT NULL DEFAULT 0.00 COMMENT 'Percentage',
     `created_at`       timestamp     NOT NULL DEFAULT current_timestamp(),
     `include_group`    tinyint(1) DEFAULT 1,
     `partner_group_id` varchar(50)            DEFAULT NULL,
     `read_only`        tinyint(1) NOT NULL DEFAULT 1,
     `sort_order`       int(11) NOT NULL DEFAULT 0 COMMENT 'Display order on Ownership page'
);

DROP TABLE IF EXISTS `company_selected_banks`;

CREATE TABLE `company_selected_banks` (
    `company_id` int(10) UNSIGNED NOT NULL,
    `country`    varchar(100) NOT NULL,
    `bank`       varchar(200) NOT NULL,
    `sort_order` int(10) UNSIGNED NOT NULL DEFAULT 0
);

DROP TABLE IF EXISTS `company_selected_countries`;

CREATE TABLE `company_selected_countries` (
    `company_id` int(10) UNSIGNED NOT NULL,
    `country`    varchar(100) NOT NULL,
    `sort_order` int(10) UNSIGNED NOT NULL DEFAULT 0
);

DROP TABLE IF EXISTS `country_bank`;

CREATE TABLE `country_bank` (
    `id`         int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `company_id` int(10) UNSIGNED NOT NULL COMMENT 'FK company.id',
    `country`    varchar(100) NOT NULL COMMENT 'Country code (e.g. AA)',
    `bank`       varchar(100) NOT NULL COMMENT 'Bank code (e.g. CC)',
    `created_at` datetime     NOT NULL DEFAULT current_timestamp() COMMENT 'Created at'
);

DROP TABLE IF EXISTS `currency`;

CREATE TABLE `currency` (
    `id`          int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `code`        varchar(10) NOT NULL,
    `company_id`  int(10) UNSIGNED NOT NULL COMMENT 'FK company.id',
    `scope_type`  enum('company','group') NOT NULL DEFAULT 'company' COMMENT 'Tenant scope: company or group ledger',
    `scope_id`    bigint UNSIGNED DEFAULT NULL COMMENT 'Numeric scope: company.id or groups.id',
    `sync_source` enum('manual','subsidiary') NOT NULL DEFAULT 'manual' COMMENT 'Whether group-ledger currency was auto-synced from subsidiaries'
);

DROP TABLE IF EXISTS `data_captures`;

CREATE TABLE `data_captures` (
     `id`           int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,
     `company_id`   int(10) UNSIGNED NOT NULL,
     `scope_type`   enum('company','group') NOT NULL DEFAULT 'company' COMMENT 'Tenant scope: company or group ledger',
     `scope_id`     bigint UNSIGNED DEFAULT NULL COMMENT 'Numeric scope: company.id or groups.id',
     `capture_date` date NOT NULL,
     `process_id`   int(11) NOT NULL,
     `currency_id`  int(11) NOT NULL,
     `created_at`   timestamp NULL DEFAULT current_timestamp(),
     `created_by`   int(11) DEFAULT NULL,
     `user_type`    enum('user','owner') NOT NULL DEFAULT 'user',
     `remark`       text DEFAULT NULL
);

DROP TABLE IF EXISTS `data_captures_deleted`;

CREATE TABLE `data_captures_deleted` (
     `id`                  int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,
     `capture_id`          int(11) NOT NULL,
     `company_id`          int(11) NOT NULL,
     `process_id`          int(11) NOT NULL,
     `currency_id`         int(11) NOT NULL,
     `capture_date`        date NOT NULL,
     `created_at`          timestamp NULL DEFAULT NULL,
     `created_by`          int(11) DEFAULT NULL,
     `user_type`           enum('user','owner') NOT NULL DEFAULT 'user',
     `remark`              text DEFAULT NULL,
     `deleted_by_user_id`  int(11) DEFAULT NULL,
     `deleted_by_owner_id` int(11) DEFAULT NULL,
     `deleted_at`          timestamp NULL DEFAULT NULL
);

DROP TABLE IF EXISTS `data_capture_details`;

CREATE TABLE `data_capture_details` (
    `id`                    int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `company_id`            int(10) UNSIGNED NOT NULL,
    `scope_type`            enum('company','group') NOT NULL DEFAULT 'company' COMMENT 'Tenant scope: company or group ledger',
    `scope_id`              bigint UNSIGNED DEFAULT NULL COMMENT 'Numeric scope: company.id or groups.id',
    `capture_id`            int(11) NOT NULL,
    `id_product_main`       varchar(255)   DEFAULT NULL,
    `description_main`      varchar(255)   DEFAULT NULL,
    `id_product_sub`        varchar(255)   DEFAULT NULL,
    `columns_value`         text           DEFAULT NULL,
    `description_sub`       varchar(255)   DEFAULT NULL,
    `product_type`          enum('main','sub') NOT NULL DEFAULT 'main',
    `formula_variant`       tinyint(4) NOT NULL DEFAULT 1,
    `id_product`            varchar(255) NOT NULL,
    `account_id`            varchar(50)    DEFAULT NULL,
    `currency_id`           int(11) NOT NULL,
    `source_value`          text           DEFAULT NULL,
    `source_percent`        varchar(255)   DEFAULT '0',
    `enable_source_percent` tinyint(1) NOT NULL DEFAULT 1,
    `formula`               text           DEFAULT NULL,
    `processed_amount`      decimal(25, 8) DEFAULT NULL,
    `rate`                  decimal(25, 8) DEFAULT NULL,
    `display_order`         int(11) DEFAULT NULL,
    `created_at`            timestamp NULL DEFAULT current_timestamp()
);

DROP TABLE IF EXISTS `data_capture_group_draft`;

CREATE TABLE `data_capture_group_draft` (
     `id`          int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,
     `group_id`    varchar(16) NOT NULL COMMENT 'Dashboard group code, e.g. AP, IG',
     `process_key` varchar(32) NOT NULL COMMENT 'salary | commission | bonus',
     `currency_id` int UNSIGNED NOT NULL COMMENT 'Currency FK (matches capture form currency_id)',
     `draft_json`  longtext    NOT NULL COMMENT 'JSON: tableData + captureType + savedAt',
     `updated_by`  int UNSIGNED DEFAULT NULL COMMENT 'Last editor user_id',
     `updated_at`  datetime    NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
     UNIQUE KEY    `uk_dc_group_draft_group_process_currency` (`group_id`, `process_key`, `currency_id`),
     KEY           `idx_dc_group_draft_updated` (`updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Group-only Data Capture table drafts';

DROP TABLE IF EXISTS `data_capture_submit_queue`;

CREATE TABLE `data_capture_submit_queue` (
     `id`            int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,
     `company_id`    int(11) NOT NULL,
     `user_id`       int(11) DEFAULT NULL,
     `status`        varchar(20) NOT NULL DEFAULT 'processing',
     `request_json`  longtext    NOT NULL,
     `capture_id`    int(11) DEFAULT NULL,
     `rows_count`    int(11) NOT NULL DEFAULT 0,
     `error_message` text                 DEFAULT NULL,
     `created_at`    datetime             DEFAULT current_timestamp(),
     `finished_at`   datetime             DEFAULT NULL
);

DROP TABLE IF EXISTS `data_capture_summary_state`;

CREATE TABLE `data_capture_summary_state` (
      `id`                  int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,
      `company_id`  int(11) NOT NULL,
      `process_key` varchar(255) NOT NULL,
      `state_json`  longtext     NOT NULL,
      `updated_at`  datetime DEFAULT current_timestamp() ON UPDATE current_timestamp()
);

DROP TABLE IF EXISTS `data_capture_templates`;

CREATE TABLE `data_capture_templates` (
      `id`                  int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,
      `company_id`            int(10) UNSIGNED NOT NULL,
      `scope_type`            enum('company','group') NOT NULL DEFAULT 'company' COMMENT 'Tenant scope: company or group ledger',
      `scope_id`              bigint UNSIGNED DEFAULT NULL COMMENT 'Numeric scope: company.id or groups.id',
      `process_id`            varchar(50)                                                     DEFAULT NULL,
      `source_columns`        text                                                            DEFAULT NULL,
      `batch_selection`       varchar(255)                                                    DEFAULT NULL,
      `columns_display`       text                                                            DEFAULT NULL,
      `data_capture_id`       int(11) DEFAULT NULL,
      `row_index`             int(11) DEFAULT NULL,
      `sub_order`             decimal(11, 2)                                                  DEFAULT NULL,
      `id_product`            varchar(255)                                           NOT NULL,
      `product_type`          enum('main','sub') NOT NULL DEFAULT 'main',
      `formula_variant`       tinyint(4) NOT NULL DEFAULT 1,
      `parent_id_product`     varchar(255)                                                    DEFAULT NULL,
      `template_key`          varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT '',
      `description`           varchar(255)                                                    DEFAULT NULL,
      `account_id`            int(11) NOT NULL,
      `account_display`       varchar(255)                                                    DEFAULT NULL,
      `currency_id`           int(11) DEFAULT NULL,
      `currency_display`      varchar(255)                                                    DEFAULT NULL,
      `formula_operators`     text                                                            DEFAULT NULL,
      `input_method`          varchar(100)                                                    DEFAULT NULL,
      `formula_display`       varchar(255)                                                    DEFAULT NULL,
      `last_source_value`     text                                                            DEFAULT NULL,
      `last_processed_amount` decimal(25, 8)                                                  DEFAULT NULL,
      `source_percent`        varchar(255)                                                    DEFAULT '0',
      `enable_source_percent` tinyint(1) DEFAULT 1,
      `enable_input_method`   tinyint(1) DEFAULT 0,
      `updated_at`            timestamp NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
      `created_at`            timestamp NULL DEFAULT current_timestamp()
);

DROP TABLE IF EXISTS `day`;

CREATE TABLE `day` (
    `id`       int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `day_name` varchar(20) DEFAULT NULL COMMENT 'Day name, e.g. Monday, Tuesday, etc.'
);

DROP TABLE IF EXISTS `process`;

CREATE TABLE `process` (
       `id`                     int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,
       `process_id`             varchar(50) NOT NULL,
       `description_id`         int         NOT NULL,
       `currency_id`            int         NOT NULL,
       `remove_word`            text                 DEFAULT NULL COMMENT 'Comma-separated words to remove',
       `replace_word_from`      varchar(255)         DEFAULT NULL,
       `replace_word_to`        varchar(255)         DEFAULT NULL,
       `remark`                 text                 DEFAULT NULL,
       `status`                 enum('active','inactive') NOT NULL DEFAULT 'active',
       `dts_modified`           datetime             DEFAULT current_timestamp() ON UPDATE current_timestamp(),
       `modified_by`            int                  DEFAULT NULL,
       `modified_by_type`       enum('user','owner') DEFAULT 'user',
       `modified_by_owner_id`   int UNSIGNED DEFAULT NULL,
       `dts_created`            datetime    NOT NULL DEFAULT current_timestamp(),
       `created_by`             int                  DEFAULT NULL,
       `created_by_type`        enum('user','owner') NOT NULL DEFAULT 'user',
       `created_by_owner_id`    int                  DEFAULT NULL,
       `company_id`             int UNSIGNED NOT NULL COMMENT 'FK company.id',
       `sync_source_process_id` int                  DEFAULT NULL COMMENT 'Multi-use formula sync source',
       KEY                      `idx_process_company` (`company_id`),
       KEY                      `idx_process_code` (`process_id`,`company_id`),
       KEY                      `idx_process_description` (`description_id`,`company_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Process records';

DROP TABLE IF EXISTS `process_day`;

CREATE TABLE `process_day` (
    `id`         int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `process_id` int NOT NULL COMMENT 'FK process.id',
    `day_id`     int NOT NULL COMMENT 'FK day.id',
    UNIQUE KEY   `uk_process_day` (`process_id`,`day_id`),
    KEY          `idx_process_day_day` (`day_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Process-day association';

DROP TABLE IF EXISTS `deleted_logs`;

CREATE TABLE `deleted_logs` (
    `id`           int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `user`         varchar(100)          DEFAULT NULL,
    `company_id`   varchar(50)           DEFAULT NULL,
    `page`         varchar(100)          DEFAULT NULL,
    `table_name`   varchar(100) NOT NULL,
    `record_id`    varchar(100)          DEFAULT NULL,
    `action_type`  varchar(50)  NOT NULL DEFAULT 'DELETE',
    `ip_address`   varchar(45)           DEFAULT NULL,
    `deleted_data` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL CHECK (json_valid(`deleted_data`)),
    `created_at`   datetime     NOT NULL DEFAULT current_timestamp()
);

DROP TABLE IF EXISTS `description`;

CREATE TABLE `description` (
   `id`         int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,
   `name`       varchar(255) NOT NULL,
   `company_id` int(10) UNSIGNED NOT NULL COMMENT 'FK company.id',
   `scope_type` enum('company','group') NOT NULL DEFAULT 'company' COMMENT 'Tenant scope: company or group ledger',
   `scope_id`   bigint UNSIGNED DEFAULT NULL COMMENT 'Numeric scope: company.id or groups.id'
);

DROP TABLE IF EXISTS `domain_list_fee_settings`;

CREATE TABLE `domain_list_fee_settings` (
    `id`            TINYINT UNSIGNED NOT NULL PRIMARY KEY DEFAULT 1,
    `company_price` JSON NOT NULL COMMENT 'Company prices by period',
    `group_price`   JSON NOT NULL COMMENT 'Group prices by period',
    `updated_at`    TIMESTAMP NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
    CONSTRAINT `chk_domain_fee_singleton` CHECK (`id` = 1),
    CONSTRAINT `chk_company_prices_shape` CHECK (json_valid(`company_price`)),
    CONSTRAINT `chk_group_prices_shape` CHECK (json_valid(`group_price`))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='C168 global domain list fee / auto-renew price config (singleton)';

DROP TABLE IF EXISTS `groups`;

CREATE TABLE `groups` (
      `id`                    int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,
      `group_code`            varchar(50) NOT NULL COMMENT 'Business group code e.g. AP, IG',
      `group_name`            varchar(100)         DEFAULT NULL,
      `status`                enum('active','inactive') NOT NULL DEFAULT 'active',
      `owner_id`              int UNSIGNED DEFAULT NULL COMMENT 'FK owner.id (domain owner)',
      `expiration_date`       date                 DEFAULT NULL COMMENT 'Group subscription expiry',
      `permissions`           longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'Group permissions JSON' CHECK (json_valid(`permissions`) OR `permissions` IS NULL),
      `fee_share_allocations` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'Fee share JSON' CHECK (json_valid(`fee_share_allocations`) OR `fee_share_allocations` IS NULL),
      `created_by`            varchar(50)          DEFAULT NULL,
      `created_at`            timestamp   NOT NULL DEFAULT current_timestamp(),
      `updated_at`            timestamp NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
      UNIQUE KEY              `uk_groups_group_code` (`group_code`),
      KEY                     `idx_groups_owner_id` (`owner_id`),
      KEY                     `idx_groups_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Group tenants (login scope group)';

DROP TABLE IF EXISTS `group_company_map`;

CREATE TABLE `group_company_map` (
     `id`         int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,
     `group_id`   bigint UNSIGNED NOT NULL COMMENT 'FK groups.id',
     `company_id` int UNSIGNED NOT NULL COMMENT 'FK company.id (subsidiary)',
     `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
     UNIQUE KEY   `uk_group_company` (`group_id`,`company_id`),
     KEY          `idx_gcm_company_id` (`company_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Group to subsidiary company mapping';

DROP TABLE IF EXISTS `account_group_map`;

CREATE TABLE `account_group_map` (
     `id`         int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,
     `account_id` int UNSIGNED NOT NULL COMMENT 'FK account.id (member)',
     `group_id`   bigint UNSIGNED NOT NULL COMMENT 'FK groups.id',
     `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
     UNIQUE KEY   `uk_account_group` (`account_id`,`group_id`),
     KEY          `idx_agm_group_id` (`group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Member account access for group login';

DROP TABLE IF EXISTS `tenant_module_policy`;

CREATE TABLE `tenant_module_policy` (
    `id`         int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `scope_type` enum('company','group') NOT NULL,
    `scope_id`   bigint UNSIGNED NOT NULL COMMENT 'company.id or groups.id',
    `module_key` varchar(50) NOT NULL COMMENT 'e.g. process, bankprocess',
    `is_enabled` tinyint(1) NOT NULL DEFAULT 1,
    `created_at` timestamp   NOT NULL DEFAULT current_timestamp(),
    `updated_at` timestamp NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
    UNIQUE KEY   `uk_tenant_module` (`scope_type`,`scope_id`,`module_key`),
    KEY          `idx_tenant_module_scope` (`scope_type`,`scope_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Per-tenant module enablement';

DROP TABLE IF EXISTS `company_auto_renew_request`;

CREATE TABLE `company_auto_renew_request` (
      `id`                  int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,
      `entity_type`         enum('company','group') NOT NULL DEFAULT 'company' COMMENT 'Tenant type',
      `company_id`          int UNSIGNED DEFAULT NULL COMMENT 'FK company.id when entity_type=company',
      `group_id`            bigint UNSIGNED DEFAULT NULL COMMENT 'FK groups.id when entity_type=group',
      `expiration_snapshot` date     NOT NULL,
      `status`              enum('pending','approved','rejected') NOT NULL DEFAULT 'pending',
      `period`              varchar(20)       DEFAULT NULL,
      `price`               decimal(25, 8)    DEFAULT NULL,
      `from_account_id`     int               DEFAULT NULL,
      `to_account_id`       int               DEFAULT NULL,
      `transaction_id`      int               DEFAULT NULL,
      `new_expiration_date` date              DEFAULT NULL,
      `processed_by`        varchar(50)       DEFAULT NULL,
      `processed_at`        datetime          DEFAULT NULL,
      `reject_reason`       varchar(255)      DEFAULT NULL,
      `created_at`          datetime NOT NULL DEFAULT current_timestamp(),
      `updated_at`          datetime NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
      UNIQUE KEY `uq_auto_renew_company_exp` (`company_id`,`expiration_snapshot`),
      UNIQUE KEY `uq_auto_renew_group_exp` (`group_id`,`expiration_snapshot`),
      KEY                   `idx_auto_renew_status` (`status`),
      KEY                   `idx_auto_renew_group` (`group_id`)
);

DROP TABLE IF EXISTS `company_ownership_history`;

CREATE TABLE `company_ownership_history` (
     `id`               int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,
     `company_id`       int UNSIGNED NOT NULL COMMENT 'FK company.id',
     `effective_month`  date          NOT NULL COMMENT 'First day of month YYYY-MM-01',
     `account_id`       int UNSIGNED NOT NULL COMMENT 'FK account.id (member)',
     `owner_type`       enum('account','owner','user','group') NOT NULL DEFAULT 'account',
     `percentage`       decimal(6, 2) NOT NULL DEFAULT 0.00,
     `partner_group_id` varchar(50)            DEFAULT NULL,
     `read_only`        tinyint(1) NOT NULL DEFAULT 1,
     `saved_by`         int UNSIGNED DEFAULT NULL,
     `saved_at`         timestamp     NOT NULL DEFAULT current_timestamp(),
     UNIQUE KEY         `uq_co_hist_month_account` (`company_id`,`effective_month`,`account_id`,`owner_type`),
     KEY                `idx_co_hist_company_month` (`company_id`,`effective_month`)
);

DROP TABLE IF EXISTS `group_ownership_history`;

CREATE TABLE `group_ownership_history` (
    `id`               int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `group_id`         varchar(50)   NOT NULL COMMENT 'Group code (matches groups.group_code)',
    `owner_id`         int           NOT NULL DEFAULT 0,
    `effective_month`  date          NOT NULL,
    `account_id`       int           NOT NULL,
    `owner_type`       enum('owner','user','group') NOT NULL DEFAULT 'owner',
    `percentage`       decimal(6, 2) NOT NULL DEFAULT 0.00,
    `partner_group_id` varchar(50)            DEFAULT NULL,
    `read_only`        tinyint(1) NOT NULL DEFAULT 1,
    `saved_by`         int                    DEFAULT NULL,
    `saved_at`         timestamp     NOT NULL DEFAULT current_timestamp(),
    UNIQUE KEY         `uq_go_hist_month_account` (`group_id`,`effective_month`,`account_id`,`owner_type`),
    KEY                `idx_go_hist_group_month` (`group_id`,`effective_month`)
);

DROP TABLE IF EXISTS `group_ownership`;

CREATE TABLE `group_ownership` (
    `id`               int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `group_id`         varchar(50)   NOT NULL,
    `owner_id`         int(11) NOT NULL,
    `account_id`       int(11) NOT NULL,
    `owner_type`       enum('owner','user','group') NOT NULL DEFAULT 'owner',
    `percentage`       decimal(6, 2) NOT NULL DEFAULT 0.00,
    `partner_group_id` varchar(50)            DEFAULT NULL,
    `read_only`        tinyint(1) NOT NULL DEFAULT 1,
    `sort_order`       int(11) NOT NULL DEFAULT 0 COMMENT 'Display order on Ownership page',
    `created_at`       timestamp NULL DEFAULT current_timestamp(),
    `updated_at`       timestamp NULL DEFAULT current_timestamp() ON UPDATE current_timestamp()
);

DROP TABLE IF EXISTS `maintenance_marquee`;

CREATE TABLE `maintenance_marquee` (
  `id` int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `prefix` varchar(100) NOT NULL COMMENT 'Marquee label shown before content',
  `content` text NOT NULL COMMENT 'Maintenance content text',
  `company_code` varchar(50) NOT NULL DEFAULT 'C168' COMMENT 'Company code, only C168 visible',
  `status` enum('active','inactive') NOT NULL DEFAULT 'active' COMMENT 'Maintenance content status',
  `created_by` int(11) NOT NULL COMMENT 'Created by user ID',
  `user_type` enum('user','owner') NOT NULL DEFAULT 'user' COMMENT 'Created by type: user or owner',
  `created_at` timestamp NULL DEFAULT current_timestamp() COMMENT 'Created at',
  `updated_at` timestamp NULL DEFAULT current_timestamp() ON UPDATE current_timestamp() COMMENT 'Updated at'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Maintenance marquee (related to company)';

DROP TABLE IF EXISTS `owner`;

CREATE TABLE `owner` (
     `id`                 int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,
     `owner_code`         varchar(50)  NOT NULL COMMENT 'Business identifier for the owner',
     `name`               varchar(150) NOT NULL,
     `email`              varchar(150)          DEFAULT NULL,
     `password`           varchar(255) NOT NULL COMMENT 'Hashed password',
     `secondary_password` varchar(255)          DEFAULT NULL COMMENT 'Hashed secondary password (6 digits)',
     `status`             enum('active','inactive') NOT NULL DEFAULT 'active',
     `created_by`         varchar(50)           DEFAULT NULL,
     `created_at`         timestamp    NOT NULL DEFAULT current_timestamp()
);

DROP TABLE IF EXISTS `password_reset_tac`;

CREATE TABLE `password_reset_tac` (
    `email`      varchar(255) NOT NULL,
    `company_id` int(11) NOT NULL,
    `code`       varchar(10)  NOT NULL,
    `expires_at` datetime     NOT NULL,
    `created_at` datetime     NOT NULL DEFAULT current_timestamp()
);

DROP TABLE IF EXISTS `password_reset_tac_owner`;

CREATE TABLE `password_reset_tac_owner` (
    `email`      varchar(255) NOT NULL,
    `owner_id`   int(10) UNSIGNED NOT NULL,
    `code`       varchar(10)  NOT NULL,
    `expires_at` datetime     NOT NULL,
    `created_at` datetime     NOT NULL DEFAULT current_timestamp()
);

DROP TABLE IF EXISTS `user`;

CREATE TABLE `user` (
    `id`                     int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `login_id`               varchar(50)  NOT NULL,
    `name`                   varchar(100) NOT NULL,
    `password`               varchar(255) NOT NULL,
    `secondary_password`     varchar(255) DEFAULT NULL COMMENT 'Hashed secondary password (6 digits)',
    `email`                  varchar(100) NOT NULL,
    `role`                   enum('admin','manager','supervisor','accountant','audit','customer service','partnership') NOT NULL,
    `permissions`            longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL CHECK (json_valid(`permissions`)),
    `status`                 enum('active','inactive') NOT NULL DEFAULT 'active',
    `created_by`             varchar(50)  DEFAULT NULL,
    `created_at`             datetime     DEFAULT current_timestamp(),
    `last_login`             datetime     DEFAULT NULL,
    `remember_token`         varchar(64)  DEFAULT NULL,
    `remember_token_expires` datetime     DEFAULT NULL,
    `read_only`              tinyint(1) NOT NULL DEFAULT 1,
    UNIQUE KEY `email` (`email`)
);

DROP TABLE IF EXISTS `user_company_map`;

CREATE TABLE `user_company_map` (
    `id`         int(10) UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `user_id`    int(11) NOT NULL,
    `company_id` int(10) UNSIGNED NOT NULL,
    `scope_type` enum('company','group') NOT NULL DEFAULT 'company' COMMENT 'Tenant scope: company or group ledger',
    `scope_id`   bigint UNSIGNED DEFAULT NULL COMMENT 'Numeric scope: company.id or groups.id',
    UNIQUE KEY `uniq_user_company` (`user_id`,`company_id`),
    KEY `fk_uc_company` (`company_id`),
    KEY `idx_ucm_scope` (`user_id`,`scope_type`,`scope_id`),
    CONSTRAINT `fk_uc_company` FOREIGN KEY (`company_id`) REFERENCES `company` (`id`) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `fk_uc_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `user_company_permissions`;

CREATE TABLE `user_company_permissions` (
    `id`                  int(11) NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `user_id`             int(11) NOT NULL COMMENT 'FK user.id',
    `company_id`          int(10) UNSIGNED NOT NULL COMMENT 'FK company.id',
    `account_permissions` text               DEFAULT NULL COMMENT 'Account permissions (JSON array), null means no permissions (empty array), [] means all permissions, there may be additional permissions',
    `process_permissions` text               DEFAULT NULL COMMENT 'Process permissions (JSON array), null means no permissions (empty array), [] means all permissions, there may be additional permissions',
    `created_at`          timestamp NOT NULL DEFAULT current_timestamp() COMMENT 'Created at',
    `updated_at`          timestamp NULL DEFAULT current_timestamp() ON UPDATE current_timestamp() COMMENT 'Updated at',
    UNIQUE KEY `unique_user_company` (`user_id`,`company_id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_company_id` (`company_id`),
    CONSTRAINT `fk_user_company_permissions_company` FOREIGN KEY (`company_id`) REFERENCES `company` (`id`) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `fk_user_company_permissions_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='User-company permissions (related to user, company)';

DROP TABLE IF EXISTS `user_group_map`;

CREATE TABLE `user_group_map` (
    `id`         bigint UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `user_id`    int(11) NOT NULL COMMENT 'FK user.id',
    `group_id`   bigint UNSIGNED NOT NULL COMMENT 'FK groups.id',
    `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
    UNIQUE KEY `uk_user_group` (`user_id`,`group_id`),
    KEY `idx_ugm_group_id` (`group_id`),
    KEY `idx_ugm_user_id` (`user_id`),
    CONSTRAINT `fk_ugm_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `fk_ugm_group` FOREIGN KEY (`group_id`) REFERENCES `groups` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='User access for group login';

DROP TABLE IF EXISTS `bank_process`;

CREATE TABLE `bank_process` (
    `id`                                    int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `company_id`                            int(10) UNSIGNED NOT NULL COMMENT 'FK company.id',
    `country`                               varchar(100)         DEFAULT NULL COMMENT 'Country',
    `bank`                                  varchar(100)         DEFAULT NULL COMMENT 'Bank name',
    `type`                                  varchar(100)         DEFAULT NULL COMMENT 'Type',
    `name`                                  varchar(255)         DEFAULT NULL COMMENT 'Name',
    `card_merchant_id`                      int(11) DEFAULT NULL COMMENT 'FK account.id (card merchant)',
    `customer_id`                           int(11) DEFAULT NULL COMMENT 'FK account.id (customer)',
    `profit_account_id`                     int(11) DEFAULT NULL COMMENT 'FK account.id (profit)',
    `contract`                              varchar(20)          DEFAULT NULL COMMENT 'Contract (e.g. 1, 2, 3, 6 months)',
    `insurance`                             decimal(25, 8)       DEFAULT NULL,
    `sop`                                   text                 DEFAULT NULL,
    `remark`                                varchar(500)         DEFAULT NULL,
    `cost`                                  decimal(25, 8)       DEFAULT NULL,
    `price`                                 decimal(25, 8)       DEFAULT NULL,
    `profit`                                decimal(25, 8)       DEFAULT NULL,
    `profit_sharing`                        text                 DEFAULT NULL COMMENT 'Profit sharing (e.g. "BB - 4, AA - 10")',
    `day_start`                             date                 DEFAULT NULL COMMENT 'Day start date',
    `day_start_frequency`                   varchar(30) NOT NULL DEFAULT '1st_of_every_month' COMMENT '1st_of_every_month=First of every month; monthly=First of every month (day_start date - 1)',
    `day_end`                               date                 DEFAULT NULL COMMENT 'Contract end date',
    `status`                                enum('active','inactive','waiting') NOT NULL DEFAULT 'active' COMMENT 'Status: active=Active, inactive=Inactive, waiting=Waiting',
    `issue_flag`                            varchar(20)          DEFAULT NULL,
    `dts_modified`                          datetime             DEFAULT current_timestamp() ON UPDATE current_timestamp(),
    `modified_by`                           int(11) DEFAULT NULL COMMENT 'FK user.id',
    `modified_by_type`                      enum('user','owner') DEFAULT 'user',
    `modified_by_owner_id`                  int(10) UNSIGNED DEFAULT NULL,
    `dts_created`                           datetime    NOT NULL DEFAULT current_timestamp(),
    `created_by`                            int(11) DEFAULT NULL COMMENT 'FK user.id',
    `created_by_type`                       enum('user','owner') NOT NULL DEFAULT 'user',
    `created_by_owner_id`                   int(11) DEFAULT NULL,
    `accounting_resend_relax_created_floor` tinyint(1) NOT NULL DEFAULT 0 COMMENT '1=Resend Inbox relax created floor',
    `accounting_resend_schedule_day_start`  date                 DEFAULT NULL,
    `accounting_resend_schedule_day_end`    date                 DEFAULT NULL,
    `accounting_resend_schedule_frequency`  varchar(40)          DEFAULT NULL COMMENT 'monthly or 1st_of_every_month, relax created floor'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Bank process accounting resend daily guard (related to process, accounting resend daily guard)';

DROP TABLE IF EXISTS `bank_process_accounting_resend_daily_guard`;

CREATE TABLE `bank_process_accounting_resend_daily_guard` (
    `id`               int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `company_id`       int(11) NOT NULL,
    `bank_process_id`  int(11) NOT NULL,
    `resend_day_start` date NOT NULL,
    `guard_date`       date NOT NULL,
    `created_at`       timestamp NULL DEFAULT current_timestamp()
);

DROP TABLE IF EXISTS `bank_process_maintenance_resend_pending`;

CREATE TABLE `bank_process_maintenance_resend_pending` (
    `id`                           int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `company_id`                   int(11) NOT NULL,
    `bank_process_id`              int(11) NOT NULL,
    `process_accounting_posted_id` int(11) DEFAULT NULL,
    `period_type`                  varchar(64) NOT NULL DEFAULT 'monthly',
    `transaction_date`             date                 DEFAULT NULL,
    `created_at`                   timestamp NULL DEFAULT current_timestamp()
);

DROP TABLE IF EXISTS `process_accounting_posted`;

CREATE TABLE `process_accounting_posted` (
    `id`          int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `company_id`  int(10) UNSIGNED NOT NULL,
    `process_id`  int(11) NOT NULL,
    `posted_date` date        NOT NULL,
    `period_type` varchar(32) NOT NULL DEFAULT 'monthly' COMMENT 'monthly = full month; partial_first_month = pro-rated from day_start to end of that month',
    `created_at`  datetime    NOT NULL DEFAULT current_timestamp()
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Records which bank_process was posted to transaction on which date (for Accounting Due inbox)';

DROP TABLE IF EXISTS `submitted_processes`;

CREATE TABLE `submitted_processes` (
    `id`             int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `company_id`     int(10) UNSIGNED NOT NULL,
    `scope_type`     enum('company','group') NOT NULL DEFAULT 'company' COMMENT 'Tenant scope: company or group ledger',
    `scope_id`       bigint UNSIGNED DEFAULT NULL COMMENT 'Numeric scope: company.id or groups.id',
    `user_id`        int(11) NOT NULL,
    `user_type`      enum('user','owner') NOT NULL DEFAULT 'user',
    `process_id`     int(11) NOT NULL,
    `date_submitted` date NOT NULL,
    `capture_date`   date NOT NULL,
    `created_at`     timestamp NULL DEFAULT current_timestamp()
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Submitted processes (related to user, process)';

DROP TABLE IF EXISTS `transactions`;

CREATE TABLE `transactions` (
    `id`                              int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `company_id`                      int(11) DEFAULT NULL,
    `scope_type`                      enum('company','group') NOT NULL DEFAULT 'company' COMMENT 'Tenant scope: company or group ledger',
    `scope_id`                        bigint UNSIGNED DEFAULT NULL COMMENT 'Numeric scope: company.id or groups.id',
    `transaction_type`                enum('WIN','LOSE','PAYMENT','RECEIVE','CONTRA','CLAIM','RATE','CLEAR','ADJUSTMENT') NOT NULL,
    `account_id`                      int(11) DEFAULT NULL,
    `from_account_id`                 int(11) DEFAULT NULL,
    `currency_id`                     int(11) DEFAULT NULL COMMENT 'Currency ID',
    `amount`                          decimal(25, 8) NOT NULL,
    `transaction_date`                date           NOT NULL COMMENT 'Transaction date',
    `description`                     varchar(500) DEFAULT NULL COMMENT 'Description',
    `sms`                             varchar(500) DEFAULT NULL COMMENT 'SMS description',
    `created_by`                      int(11) DEFAULT NULL COMMENT 'FK user.id',
    `created_by_owner`                int(10) UNSIGNED DEFAULT NULL,
    `created_at`                      timestamp NULL DEFAULT current_timestamp() COMMENT 'Created at',
    `updated_at`                      timestamp NULL DEFAULT current_timestamp() ON UPDATE current_timestamp() COMMENT 'Updated at',
    `approval_status`                 enum('approved','pending') NOT NULL DEFAULT 'approved',
    `approved_by`                     int(11) DEFAULT NULL,
    `approved_by_owner`               int(10) UNSIGNED DEFAULT NULL,
    `approved_at`                     timestamp NULL DEFAULT NULL,
    `source_bank_process_id`          int(11) DEFAULT NULL COMMENT 'FK bank_process.id',
    `source_bank_process_period_type` varchar(32)  DEFAULT NULL COMMENT 'Bank process period type: monthly / partial_first_month / manual_inactive'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Transactions (related to company, scope, transaction type, account)';

DROP TABLE IF EXISTS `transactions_deleted`;

CREATE TABLE `transactions_deleted` (
    `id`                              int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `transaction_id`                  int(11) NOT NULL,
    `company_id`                      int(11) NOT NULL,
    `scope_type`                      enum('company','group') NOT NULL DEFAULT 'company' COMMENT 'Tenant scope: company or group ledger',
    `scope_id`                        bigint UNSIGNED DEFAULT NULL COMMENT 'Numeric scope: company.id or groups.id',
    `transaction_type`                enum('WIN','LOSE','PAYMENT','RECEIVE','CONTRA','RATE','ADJUSTMENT') NOT NULL,
    `account_id`                      int(11) NOT NULL COMMENT 'To Account - To account ID',
    `from_account_id`                 int(11) DEFAULT NULL COMMENT 'From Account - From account ID',
    `amount`                          decimal(25, 8) NOT NULL,
    `currency_id`                     int(11) DEFAULT NULL,
    `transaction_date`                date           NOT NULL COMMENT 'Transaction date',
    `description`                     varchar(500) DEFAULT NULL COMMENT 'Description',
    `sms`                             varchar(500) DEFAULT NULL COMMENT 'SMS description',
    `created_by`                      int(11) DEFAULT NULL COMMENT 'Created by user ID',
    `created_by_owner`                int(11) DEFAULT NULL COMMENT 'Created by owner ID',
    `created_at`                      timestamp NULL DEFAULT NULL COMMENT 'Created at',
    `deleted_by_user_id`              int(11) DEFAULT NULL COMMENT 'Deleted by user ID',
    `deleted_by_owner_id`             int(11) DEFAULT NULL COMMENT 'Deleted by owner ID',
    `deleted_at`                      timestamp NULL DEFAULT NULL COMMENT 'Deleted at',
    `source_bank_process_id`          int(11) DEFAULT NULL,
    `source_bank_process_period_type` varchar(64)  DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Payment Maintenance deleted transactions (related to company, scope, transaction type, account)';

DROP TABLE IF EXISTS `transactions_rate`;

CREATE TABLE `transactions_rate` (
     `id`                            int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,
     `transaction_id`                int(11) NOT NULL COMMENT 'FK transactions.id',
     `company_id`                    int(11) DEFAULT NULL,
     `rate_group_id`                 varchar(50)    NOT NULL COMMENT 'Rate group ID (first RATE transaction)',
     `rate_from_account_id`          int(11) NOT NULL COMMENT 'First row From Account ID (account1)',
     `rate_to_account_id`            int(11) NOT NULL COMMENT 'First row To Account ID (account2)',
     `rate_from_currency_id`         int(11) NOT NULL COMMENT 'First row Currency ID (SGD)',
     `rate_from_amount`              decimal(15, 2) NOT NULL COMMENT 'First row Currency Amount (100)',
     `rate_to_currency_id`           int(11) NOT NULL COMMENT 'Second row Currency ID (MYR)',
     `rate_to_amount`                decimal(15, 2) NOT NULL COMMENT 'Second row Currency Amount (320, after middle-man deduction)',
     `exchange_rate`                 decimal(15, 6) NOT NULL COMMENT 'Exchange Rate (3.3)',
     `rate_transfer_from_account_id` int(11) DEFAULT NULL COMMENT 'Second row From Account ID (account3)',
     `rate_transfer_to_account_id`   int(11) DEFAULT NULL COMMENT 'Second row To Account ID (account4)',
     `rate_transfer_from_amount`     decimal(15, 2) DEFAULT NULL COMMENT 'Transfer From Amount (330, original price = from_amount Ã— exchange_rate)',
     `rate_transfer_to_amount`       decimal(15, 2) DEFAULT NULL COMMENT 'Transfer To Amount (320, after middle-man deduction)',
     `rate_middleman_account_id`     int(11) DEFAULT NULL COMMENT 'Middle-Man Account ID (account5)',
     `rate_middleman_rate`           decimal(15, 6) DEFAULT NULL COMMENT 'Middle-Man Rate Multiplier (0.1)',
     `rate_middleman_amount`         decimal(15, 2) DEFAULT NULL COMMENT 'Middle-Man Amount (10)',
     `created_at`                    timestamp NULL DEFAULT current_timestamp(),
     `updated_at`                    timestamp NULL DEFAULT current_timestamp() ON UPDATE current_timestamp()
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Rate transactions (related to company, rate group)';

DROP TABLE IF EXISTS `transactions_rate_details`;

CREATE TABLE `transactions_rate_details` (
     `id`              int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,
     `rate_group_id`   varchar(50)    NOT NULL COMMENT 'Rate group ID',
     `transaction_id`  int(11) NOT NULL COMMENT 'FK transactions.id',
     `company_id`      int(11) DEFAULT NULL,
     `record_type`     enum('first_from','first_to','transfer_from','transfer_to','middleman') NOT NULL,
     `account_id`      int(11) NOT NULL COMMENT 'Account ID',
     `from_account_id` int(11) DEFAULT NULL COMMENT 'From Account ID (for transfer_from and transfer_to)',
     `amount`          decimal(15, 2) NOT NULL COMMENT 'Amount (number, depends on record_type)',
     `currency_id`     int(11) NOT NULL COMMENT 'Currency ID',
     `description`     varchar(500) DEFAULT NULL,
     `created_at`      timestamp NULL DEFAULT current_timestamp()
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Rate transaction details (related to rate group, transaction)';

DROP TABLE IF EXISTS `transaction_entry`;

CREATE TABLE `transaction_entry` (
     `id`          int(255) NOT NULL AUTO_INCREMENT PRIMARY KEY,
     `header_id`   int(11) NOT NULL,
     `company_id`  int(11) DEFAULT NULL,
     `scope_type`  enum('company','group') NOT NULL DEFAULT 'company' COMMENT 'Tenant scope: company or group ledger',
     `scope_id`    bigint UNSIGNED DEFAULT NULL COMMENT 'Numeric scope: company.id or groups.id',
     `account_id`  int(11) NOT NULL,
     `currency_id` int(11) NOT NULL,
     `amount`      decimal(25, 8) NOT NULL,
     `entry_type`  enum('NORMAL_FROM','NORMAL_TO','RATE_FIRST_FROM','RATE_FIRST_TO','RATE_TRANSFER_FROM','RATE_TRANSFER_TO','RATE_MIDDLEMAN','RATE_FEE') NOT NULL,
     `description` varchar(255)            DEFAULT NULL,
     `created_at`  timestamp      NOT NULL DEFAULT current_timestamp()
);
