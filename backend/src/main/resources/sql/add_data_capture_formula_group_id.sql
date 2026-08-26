-- Formula sync (Copy From): tag formulas that were copied from / to each other so editing one
-- propagates to the rest of the group. Deliberately NOT a foreign key — it is a plain grouping
-- label, not a "points at a specific row" reference, so deleting any member (including the row
-- whose id the tag happens to equal) never disturbs the tag stored on the others.
ALTER TABLE `data_capture_formula`
    ADD COLUMN `formula_group_id` INT UNSIGNED DEFAULT NULL
        COMMENT 'Copy From 同步分组标签，非外键；同组的 formula 编辑时互相同步，删除不连带'
        AFTER `formula`,
    ADD KEY `idx_data_capture_formula_group_id` (`formula_group_id`);
