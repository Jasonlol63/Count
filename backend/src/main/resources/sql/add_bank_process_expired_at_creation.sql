-- FIRST_OF_EVERY_MONTH / MONTHLY only: 1 = 建立當下 dayEnd 所在月份已早於建立當月（整段合同從建立起就已過期），
-- 不套用「ACTIVE 時到期後仍展延生成」的邏輯；0 = 正常，維持既有行為。
ALTER TABLE `bank_process`
    ADD COLUMN `expired_at_creation` TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '建立當下合同已完全過期(dayEnd所在月 < 建立當月)，不套用到期後展延生成'
        AFTER `day_end_monthly_cap_enabled`;
