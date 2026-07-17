-- 1st of every month: Day end switch ON = DAY_END_TAIL, OFF = last month FULL_MONTH.
ALTER TABLE `bank_process`
    ADD COLUMN `day_end_monthly_cap_enabled` TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '1st of every month only: 1=last month DAY_END_TAIL to day_end; 0=last month FULL_MONTH to month end'
        AFTER `day_end`;
