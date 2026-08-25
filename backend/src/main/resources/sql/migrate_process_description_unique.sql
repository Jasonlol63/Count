-- Fixes the duplicate-description race condition (check-then-insert with no DB constraint).
-- Run once against existing databases; schema.sql already has the constraint for fresh installs.

-- 1. Re-point any process_description_link / data_capture_description rows that reference a
--    duplicate description onto the earliest (lowest id) row for the same (tenant_id, name),
--    so the duplicates can be dropped without violating FKs.
UPDATE process_description_link l
JOIN process_description dup ON dup.id = l.description_id
JOIN (
    SELECT tenant_id, name, MIN(id) AS keep_id
    FROM process_description
    GROUP BY tenant_id, name
    HAVING COUNT(*) > 1
) canon ON canon.tenant_id = dup.tenant_id AND canon.name = dup.name AND dup.id <> canon.keep_id
LEFT JOIN process_description_link existing
    ON existing.process_id = l.process_id AND existing.description_id = canon.keep_id
SET l.description_id = canon.keep_id
WHERE existing.id IS NULL;

DELETE l FROM process_description_link l
JOIN process_description dup ON dup.id = l.description_id
JOIN (
    SELECT tenant_id, name, MIN(id) AS keep_id
    FROM process_description
    GROUP BY tenant_id, name
    HAVING COUNT(*) > 1
) canon ON canon.tenant_id = dup.tenant_id AND canon.name = dup.name AND dup.id <> canon.keep_id;

UPDATE data_capture_description d
JOIN process_description dup ON dup.id = d.description_id
JOIN (
    SELECT tenant_id, name, MIN(id) AS keep_id
    FROM process_description
    GROUP BY tenant_id, name
    HAVING COUNT(*) > 1
) canon ON canon.tenant_id = dup.tenant_id AND canon.name = dup.name AND dup.id <> canon.keep_id
LEFT JOIN data_capture_description existing
    ON existing.capture_id = d.capture_id AND existing.description_id = canon.keep_id
SET d.description_id = canon.keep_id
WHERE existing.id IS NULL;

DELETE d FROM data_capture_description d
JOIN process_description dup ON dup.id = d.description_id
JOIN (
    SELECT tenant_id, name, MIN(id) AS keep_id
    FROM process_description
    GROUP BY tenant_id, name
    HAVING COUNT(*) > 1
) canon ON canon.tenant_id = dup.tenant_id AND canon.name = dup.name AND dup.id <> canon.keep_id;

-- 2. Drop the now-unreferenced duplicate rows themselves, keeping the earliest per (tenant_id, name).
DELETE dup FROM process_description dup
JOIN (
    SELECT tenant_id, name, MIN(id) AS keep_id
    FROM process_description
    GROUP BY tenant_id, name
    HAVING COUNT(*) > 1
) canon ON canon.tenant_id = dup.tenant_id AND canon.name = dup.name AND dup.id <> canon.keep_id;

-- 3. Add the unique constraint (so the DB rejects future duplicates), then drop the old
--    non-unique index. Order matters: the FK on tenant_id needs a covering index at all times,
--    so the new index must exist before the old one is dropped (MySQL #1553 otherwise).
ALTER TABLE process_description ADD UNIQUE KEY uk_process_description_tenant_name (tenant_id, name);
ALTER TABLE process_description DROP INDEX idx_description_tenant_id;
