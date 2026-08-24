-- Auto Renew delete/revert support.
-- Adds the join table that records which `transactions` rows an approve (chargeDomainFee)
-- created for a given tenant_auto_renew_request, so a later "delete" (approved -> revert to
-- pending) can remove exactly those rows instead of guessing by tenant/date. Rejected rows
-- never touch transactions/expiration, so delete on a rejected row only needs the status reset.
-- Safe to re-run (idempotent).
-- Example: mysql -u root testcount < backend/src/main/resources/sql/migrate_auto_renew_delete.sql

USE testcount;

CREATE TABLE IF NOT EXISTS `tenant_auto_renew_request_transaction` (
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
