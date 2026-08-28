-- Allow `process.code` to repeat within a tenant, as long as the description attached to each
-- repeated-code row differs. What must never repeat is (tenant, category, code, description) --
-- enforced by DB triggers, not just a Service-layer check (a Service-only check has a race-condition
-- window: two near-simultaneous requests can both pass the "not exists" check before either commits).
--
-- Background: legacy data showed the same process code split across several rows on purpose (e.g.
-- one vendor code with a Sport / Live Casino / E-Games section, each with its own remove_word/
-- replace_word parsing rule and a genuinely different Data Capture formula) -- not accidental
-- duplication. The old UNIQUE(tenant_id, category, code) constraint would have blocked recreating
-- that pattern going forward.
--
-- Safe to re-run: uses `information_schema` checks before DROP/ADD INDEX and DROP TRIGGER IF EXISTS
-- before each CREATE TRIGGER.
-- Example: mysql -u root testcount < backend/src/main/resources/sql/migrate_process_code_allow_duplicate.sql

USE testcount;

-- 1. Relax the unique constraint on `process` to a plain lookup index.
SET @idx_exists := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'process' AND INDEX_NAME = 'uk_process_tenant_category_code'
);
SET @sql := IF(@idx_exists > 0, 'ALTER TABLE `process` DROP INDEX `uk_process_tenant_category_code`', 'SELECT ''uk_process_tenant_category_code already absent, skipping''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'process' AND INDEX_NAME = 'idx_process_tenant_category_code'
);
SET @sql := IF(@idx_exists = 0, 'ALTER TABLE `process` ADD INDEX `idx_process_tenant_category_code` (`tenant_id`, `category`, `code`)', 'SELECT ''idx_process_tenant_category_code already present, skipping''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. DB-enforced replacement: no two DIFFERENT processes under the same (tenant, category, code) may
--    link the same description. `uk_proc_desc` on process_description_link already stops one process
--    from linking the same description twice; these triggers stop two different processes (same
--    code) from each linking that description.
DROP TRIGGER IF EXISTS `trg_pdl_bi_unique_code_desc`;
DROP TRIGGER IF EXISTS `trg_pdl_bu_unique_code_desc`;
DROP TRIGGER IF EXISTS `trg_process_bu_unique_code_desc`;

DELIMITER $$

CREATE TRIGGER `trg_pdl_bi_unique_code_desc`
BEFORE INSERT ON `process_description_link`
FOR EACH ROW
BEGIN
    DECLARE conflict_count INT DEFAULT 0;
    SELECT COUNT(*) INTO conflict_count
    FROM `process_description_link` l
    JOIN `process` p1 ON p1.id = l.process_id
    JOIN `process` p2 ON p2.id = NEW.process_id
    WHERE l.description_id = NEW.description_id
      AND l.process_id <> NEW.process_id
      AND p1.tenant_id = p2.tenant_id
      AND p1.category = p2.category
      AND p1.code = p2.code;
    IF conflict_count > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Duplicate process code + description: another process with the same tenant/category/code already has this description linked';
    END IF;
END$$

CREATE TRIGGER `trg_pdl_bu_unique_code_desc`
BEFORE UPDATE ON `process_description_link`
FOR EACH ROW
BEGIN
    DECLARE conflict_count INT DEFAULT 0;
    SELECT COUNT(*) INTO conflict_count
    FROM `process_description_link` l
    JOIN `process` p1 ON p1.id = l.process_id
    JOIN `process` p2 ON p2.id = NEW.process_id
    WHERE l.description_id = NEW.description_id
      AND l.process_id <> NEW.process_id
      AND l.id <> NEW.id
      AND p1.tenant_id = p2.tenant_id
      AND p1.category = p2.category
      AND p1.code = p2.code;
    IF conflict_count > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Duplicate process code + description: another process with the same tenant/category/code already has this description linked';
    END IF;
END$$

CREATE TRIGGER `trg_process_bu_unique_code_desc`
BEFORE UPDATE ON `process`
FOR EACH ROW
BEGIN
    DECLARE conflict_count INT DEFAULT 0;
    IF NEW.code <> OLD.code OR NEW.tenant_id <> OLD.tenant_id OR NEW.category <> OLD.category THEN
        SELECT COUNT(*) INTO conflict_count
        FROM `process_description_link` l1
        JOIN `process_description_link` l2 ON l2.description_id = l1.description_id AND l2.process_id <> l1.process_id
        JOIN `process` p2 ON p2.id = l2.process_id
        WHERE l1.process_id = NEW.id
          AND p2.tenant_id = NEW.tenant_id
          AND p2.category = NEW.category
          AND p2.code = NEW.code;
        IF conflict_count > 0 THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'Duplicate process code + description: renaming this process would collide with an existing description link under the same tenant/category/code';
        END IF;
    END IF;
END$$

DELIMITER ;
