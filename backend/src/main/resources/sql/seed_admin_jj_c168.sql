USE testcount;

-- Admin JJ under tenant C168 (password: 1, no secondary password)
INSERT INTO `user` (
  `login_id`,
  `name`,
  `email`,
  `password`,
  `secondary_password`,
  `role_id`,
  `status`,
  `read_only`,
  `created_by`
)
SELECT
  'JJ',
  'JJ',
  'jj@c168.local',
  '$2y$10$DwEdouw8eKv1N2zdKD8CEOQvaFTHnkRoE/i2ovfLR0LLnNp2fWP5a',
  NULL,
  r.id,
  'ACTIVE',
  1,
  'seed'
FROM `user_role` r
WHERE r.code = 'ADMIN'
  AND NOT EXISTS (
    SELECT 1 FROM `user` WHERE UPPER(TRIM(login_id)) = 'JJ'
  );

INSERT INTO `user_tenant_access` (`user_id`, `tenant_id`, `account_permissions`, `process_permissions`)
SELECT u.id, t.id, NULL, NULL
FROM `user` u
JOIN `tenant` t ON UPPER(TRIM(t.code)) = 'C168'
WHERE UPPER(TRIM(u.login_id)) = 'JJ'
  AND NOT EXISTS (
    SELECT 1
    FROM `user_tenant_access` uta
    WHERE uta.user_id = u.id
      AND uta.tenant_id = t.id
  );
