-- Copy From feature: track which process a given process was duplicated from (audit/debug trail only).
-- ON DELETE SET NULL is required: deleting the source process must NEVER cascade-delete or be blocked
-- by processes that were copied from it — they are fully independent rows after creation.
ALTER TABLE `process`
    ADD COLUMN `copied_from_process_id` INT UNSIGNED DEFAULT NULL
        COMMENT '来源 process.id（Copy From 建立时记录，仅用于追溯/排查，不影响业务逻辑）'
        AFTER `code`,
    ADD KEY `idx_process_copied_from` (`copied_from_process_id`),
    ADD CONSTRAINT `fk_process_copied_from`
        FOREIGN KEY (`copied_from_process_id`) REFERENCES `process` (`id`)
        ON DELETE SET NULL;
