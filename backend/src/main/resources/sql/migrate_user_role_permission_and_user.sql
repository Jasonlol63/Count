USE testcount;

SET FOREIGN_KEY_CHECKS = 0;

-- user_role_permission
DROP TABLE IF EXISTS `user_role_permission`;

CREATE TABLE `user_role_permission` (
  `role_id`       TINYINT UNSIGNED NOT NULL,
  `permission_id` SMALLINT UNSIGNED NOT NULL,
  PRIMARY KEY (`role_id`, `permission_id`),
  KEY `idx_urp_permission_id` (`permission_id`),
  CONSTRAINT `fk_urp_role` FOREIGN KEY (`role_id`) REFERENCES `user_role` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_urp_permission` FOREIGN KEY (`permission_id`) REFERENCES `permission` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Default sidebar permissions per admin role';

INSERT INTO `user_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id
FROM `user_role` r
JOIN `permission` p ON p.code IN (
  'HOME', 'ADMIN', 'ACCOUNT', 'OWNERSHIP', 'PROCESS', 'DATACAPTURE', 'PAYMENT', 'REPORT', 'MAINTENANCE'
)
WHERE r.code IN ('OWNER', 'PARTNERSHIP', 'ADMIN');

INSERT INTO `user_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id
FROM `user_role` r
JOIN `permission` p ON p.code IN (
  'ADMIN', 'ACCOUNT', 'PROCESS', 'DATACAPTURE', 'PAYMENT', 'REPORT', 'MAINTENANCE'
)
WHERE r.code = 'MANAGER';

INSERT INTO `user_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id
FROM `user_role` r
JOIN `permission` p ON p.code IN (
  'ADMIN', 'ACCOUNT', 'PROCESS', 'DATACAPTURE', 'PAYMENT', 'REPORT'
)
WHERE r.code = 'SUPERVISOR';

INSERT INTO `user_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id
FROM `user_role` r
JOIN `permission` p ON p.code IN ('ACCOUNT', 'PROCESS', 'PAYMENT', 'REPORT')
WHERE r.code = 'ACCOUNTANT';

INSERT INTO `user_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id
FROM `user_role` r
JOIN `permission` p ON p.code IN ('PAYMENT', 'REPORT', 'MAINTENANCE')
WHERE r.code = 'AUDIT';

INSERT INTO `user_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id
FROM `user_role` r
JOIN `permission` p ON p.code IN (
  'ACCOUNT', 'PROCESS', 'DATACAPTURE', 'PAYMENT', 'REPORT'
)
WHERE r.code = 'CUSTOMER_SERVICE';

-- user (role_id replaces legacy role ENUM + permissions JSON)
DROP TABLE IF EXISTS `user`;

CREATE TABLE `user` (
  `id`                     INT UNSIGNED NOT NULL AUTO_INCREMENT,
  `login_id`               VARCHAR(50)  NOT NULL COMMENT 'Login identifier (Admin tab)',
  `name`                   VARCHAR(100) NOT NULL,
  `email`                  VARCHAR(100) NOT NULL,
  `password`               VARCHAR(255) NOT NULL COMMENT 'BCrypt hash',
  `secondary_password`     VARCHAR(255)          DEFAULT NULL COMMENT 'BCrypt, C168 optional 6-digit PIN',
  `role_id`                TINYINT UNSIGNED NOT NULL COMMENT 'FK user_role.id',
  `status`                 ENUM('ACTIVE', 'INACTIVE') NOT NULL DEFAULT 'ACTIVE',
  `read_only`              TINYINT(1)   NOT NULL DEFAULT 1,
  `remember_token`         VARCHAR(64)           DEFAULT NULL,
  `remember_token_expires` DATETIME              DEFAULT NULL,
  `last_login`             DATETIME              DEFAULT NULL,
  `created_by`             VARCHAR(50)           DEFAULT NULL,
  `created_at`             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_login_id` (`login_id`),
  UNIQUE KEY `uk_user_email` (`email`),
  KEY `idx_user_status` (`status`),
  KEY `idx_user_role_id` (`role_id`),
  CONSTRAINT `fk_user_role` FOREIGN KEY (`role_id`) REFERENCES `user_role` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Admin / staff identity';

SET FOREIGN_KEY_CHECKS = 1;
