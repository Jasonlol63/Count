-- Save Draft for GAME category processes: per-process opt-in switch. GAME process codes are
-- user-created free text (unlike BANK, where PROFIT/SALARY/COMMISSION/BONUS are fixed and
-- hardcoded), so draft eligibility can't be matched by code/name. Instead the admin explicitly
-- flips this switch per process when creating/editing it. BANK rows ignore this column and keep
-- using the existing hardcoded process-code whitelist.
ALTER TABLE `process`
    ADD COLUMN `enable_save_draft` TINYINT(1) NOT NULL DEFAULT 0
        COMMENT 'GAME 专用：是否启用 Save Draft，默认关闭；BANK 不使用此字段'
        AFTER `copied_from_process_id`;
