-- Add prefix column to maintenance_marquee (run once on existing DBs).
-- Skip if the table was created from an updated schema.sql that already includes prefix.

ALTER TABLE `maintenance_marquee`
  ADD COLUMN `prefix` VARCHAR(100) NOT NULL DEFAULT '' COMMENT 'Marquee label shown before content'
  AFTER `id`;
