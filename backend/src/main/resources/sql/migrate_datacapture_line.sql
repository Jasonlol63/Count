-- Add data_capture_line (Summary final-submit row snapshot; replaces legacy data_capture_details).
-- Safe to run on existing testcount that already has data_captures / account / currency.
-- Example: mysql -u root testcount < backend/src/main/resources/sql/migrate_datacapture_line.sql

USE testcount;

CREATE TABLE IF NOT EXISTS `data_capture_line` (
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
    `created_at`            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_dcl_capture` (`capture_id`),
    KEY `idx_dcl_tenant_capture` (`tenant_id`, `capture_id`),
    KEY `idx_dcl_tenant_account` (`tenant_id`, `account_id`),
    KEY `idx_dcl_account` (`account_id`),
    KEY `idx_dcl_product` (`capture_id`, `id_product`, `account_id`, `formula_variant`),
    CONSTRAINT `fk_dcl_tenant`
        FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_dcl_capture`
        FOREIGN KEY (`capture_id`) REFERENCES `data_captures` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_dcl_account`
        FOREIGN KEY (`account_id`) REFERENCES `account` (`id`),
    CONSTRAINT `fk_dcl_currency`
        FOREIGN KEY (`currency_id`) REFERENCES `currency` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Data Capture Summary 最终 Submit 行快照（替代 legacy data_capture_details；无 scope_*）';
