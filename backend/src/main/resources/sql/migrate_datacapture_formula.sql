-- Add datacapture_formula (Summary populate + Formula Maintenance; hard DELETE).
-- Safe to run on existing testcount that already has data_captures / process.
-- Example: mysql -u root testcount < backend/src/main/resources/sql/migrate_datacapture_formula.sql

USE testcount;

CREATE TABLE IF NOT EXISTS `datacapture_formula` (
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
    `formula`               TEXT DEFAULT NULL COMMENT '公式表达式',
    `formula_operators`     TEXT DEFAULT NULL COMMENT '公式运算符片段（可选）',
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
