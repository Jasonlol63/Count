-- Migrate announcements.created_by / maintenance_marquee.created_by (FK -> user.id/owner.id)
-- to VARCHAR login_id, matching the created_by(login_id) convention used elsewhere
-- (data_captures, process, process_submitted, ...). Root cause: storing the raw user_id
-- forced the frontend to display a numeric id instead of a name with no join available.
--
-- Order matters: widen the column to VARCHAR first (MySQL preserves the old numeric id
-- as its string form, e.g. 5 -> "5"), then backfill from user/owner by joining that
-- string id back to user.id/owner.id (MySQL coerces the comparison automatically).

SET @has_announcements_int := (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'announcements'
    AND COLUMN_NAME = 'created_by' AND DATA_TYPE IN ('int', 'integer')
);

SET @sql := IF(@has_announcements_int > 0,
  'ALTER TABLE `announcements`
     MODIFY COLUMN `created_by` VARCHAR(50) NOT NULL
       COMMENT ''Creator login_id (admin=user.login_id; owner=owner_code)''',
  'SELECT ''announcements.created_by already migrated or non-int'' AS info');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(@has_announcements_int > 0,
  'UPDATE `announcements` a
     LEFT JOIN `user` u ON a.user_type = ''USER'' AND u.id = a.created_by
     LEFT JOIN `owner` o ON a.user_type = ''OWNER'' AND o.id = a.created_by
     SET a.created_by = COALESCE(u.login_id, o.owner_code, a.created_by)',
  'SELECT ''skip announcements backfill'' AS info');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_maintenance_int := (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'maintenance_marquee'
    AND COLUMN_NAME = 'created_by' AND DATA_TYPE IN ('int', 'integer')
);

SET @sql := IF(@has_maintenance_int > 0,
  'ALTER TABLE `maintenance_marquee`
     MODIFY COLUMN `created_by` VARCHAR(50) NOT NULL
       COMMENT ''Creator login_id (admin=user.login_id; owner=owner_code)''',
  'SELECT ''maintenance_marquee.created_by already migrated or non-int'' AS info');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(@has_maintenance_int > 0,
  'UPDATE `maintenance_marquee` m
     LEFT JOIN `user` u ON m.user_type = ''USER'' AND u.id = m.created_by
     LEFT JOIN `owner` o ON m.user_type = ''OWNER'' AND o.id = m.created_by
     SET m.created_by = COALESCE(u.login_id, o.owner_code, m.created_by)',
  'SELECT ''skip maintenance_marquee backfill'' AS info');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
