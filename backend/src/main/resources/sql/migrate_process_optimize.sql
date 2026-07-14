-- Migrate process tables in testcount: JSON columns -> normalized tables/columns.
-- Preserves existing process / process_description / submitted data.
-- Compatible with MariaDB 10.4 (no JSON_TABLE).

-- 1) New link / day tables
CREATE TABLE IF NOT EXISTS `process_description_link` (
  `id`             INT UNSIGNED NOT NULL AUTO_INCREMENT,
  `process_id`     INT UNSIGNED NOT NULL COMMENT 'FK process.id',
  `description_id` INT UNSIGNED NOT NULL COMMENT 'FK process_description.id',
  `created_at`     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_proc_desc` (`process_id`, `description_id`),
  KEY `idx_pdl_description` (`description_id`),
  CONSTRAINT `fk_pdl_process` FOREIGN KEY (`process_id`) REFERENCES `process` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_pdl_description` FOREIGN KEY (`description_id`) REFERENCES `process_description` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='process ↔ description 多对多（替代 description_ids JSON）';

CREATE TABLE IF NOT EXISTS `process_day` (
  `id`          INT UNSIGNED NOT NULL AUTO_INCREMENT,
  `process_id`  INT UNSIGNED NOT NULL COMMENT 'FK process.id',
  `day_of_week` TINYINT UNSIGNED NOT NULL COMMENT '1=Mon ... 7=Sun',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_process_day` (`process_id`, `day_of_week`),
  CONSTRAINT `fk_pd_process` FOREIGN KEY (`process_id`) REFERENCES `process` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='process 运行星期（替代 schedule_days JSON）';

-- Helper numbers 0..15 for JSON array expansion
DROP TEMPORARY TABLE IF EXISTS `_json_idx`;
CREATE TEMPORARY TABLE `_json_idx` (`n` INT PRIMARY KEY);
INSERT INTO `_json_idx` (`n`) VALUES
  (0),(1),(2),(3),(4),(5),(6),(7),(8),(9),(10),(11),(12),(13),(14),(15);

-- 2) Migrate description_ids JSON -> process_description_link (if column still exists)
SET @has_desc_ids := (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'process' AND COLUMN_NAME = 'description_ids'
);
SET @sql := IF(@has_desc_ids > 0,
  'INSERT IGNORE INTO process_description_link (process_id, description_id)
   SELECT p.id, CAST(JSON_UNQUOTE(JSON_EXTRACT(p.description_ids, CONCAT(''$['', i.n, '']''))) AS UNSIGNED)
   FROM process p
   JOIN _json_idx i ON i.n < JSON_LENGTH(p.description_ids)
   WHERE p.description_ids IS NOT NULL
     AND JSON_EXTRACT(p.description_ids, CONCAT(''$['', i.n, '']'')) IS NOT NULL',
  'SELECT ''description_ids already migrated'' AS info');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3) Migrate schedule_days JSON -> process_day
SET @has_sched := (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'process' AND COLUMN_NAME = 'schedule_days'
);
SET @sql := IF(@has_sched > 0,
  'INSERT IGNORE INTO process_day (process_id, day_of_week)
   SELECT p.id, CAST(JSON_UNQUOTE(JSON_EXTRACT(p.schedule_days, CONCAT(''$['', i.n, '']''))) AS UNSIGNED)
   FROM process p
   JOIN _json_idx i ON i.n < JSON_LENGTH(p.schedule_days)
   WHERE p.schedule_days IS NOT NULL
     AND JSON_EXTRACT(p.schedule_days, CONCAT(''$['', i.n, '']'')) IS NOT NULL',
  'SELECT ''schedule_days already migrated'' AS info');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 4) Add flattened settings columns
SET @col_exists := (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'process' AND COLUMN_NAME = 'remove_word'
);
SET @sql := IF(@col_exists = 0,
  'ALTER TABLE `process`
     ADD COLUMN `remove_word` TEXT DEFAULT NULL COMMENT ''要过滤的词，逗号分隔'' AFTER `currency_id`,
     ADD COLUMN `replace_word_from` VARCHAR(255) DEFAULT NULL AFTER `remove_word`,
     ADD COLUMN `replace_word_to` VARCHAR(255) DEFAULT NULL AFTER `replace_word_from`',
  'SELECT ''columns already exist'' AS info');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 5) Migrate settings JSON -> columns
SET @settings_exists := (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'process' AND COLUMN_NAME = 'settings'
);
SET @sql := IF(@settings_exists > 0,
  'UPDATE `process`
   SET
     `remove_word` = NULLIF(JSON_UNQUOTE(JSON_EXTRACT(`settings`, ''$.remove_word'')), ''''),
     `replace_word_from` = NULLIF(JSON_UNQUOTE(JSON_EXTRACT(`settings`, ''$.replace_word_from'')), ''''),
     `replace_word_to` = NULLIF(JSON_UNQUOTE(JSON_EXTRACT(`settings`, ''$.replace_word_to'')), '''')
   WHERE `settings` IS NOT NULL',
  'SELECT ''settings already migrated'' AS info');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 6) Drop old JSON columns
SET @sql := IF(@settings_exists > 0,
  'ALTER TABLE `process`
     DROP COLUMN `description_ids`,
     DROP COLUMN `schedule_days`,
     DROP COLUMN `settings`',
  'SELECT ''json columns already dropped'' AS info');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 7) Rename submitted_processes -> process_submitted (preserve data)
SET @old_exists := (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'submitted_processes'
);
SET @new_exists := (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'process_submitted'
);
SET @sql := IF(@old_exists > 0 AND @new_exists = 0,
  'RENAME TABLE `submitted_processes` TO `process_submitted`',
  'SELECT ''process_submitted already ready'' AS info');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

ALTER TABLE `process_submitted` COMMENT = '已提交流程记录表';
ALTER TABLE `process` COMMENT = '流程配置表（无 JSON：settings/description/days 已拆表拆列）';
