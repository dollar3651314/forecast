-- forecast 基线迁移（S1，技术方案/v1-架构方案.md 第 3.4 节，AQ-9 按建议重建基线）。
-- 来源：在空的 MySQL 8.4 上跑 复制源（staging 2583328，见 技术方案/v1-架构方案.md 第 1.1 节）的 V1～V32，用 mysqldump --no-data 导出保留下来的表，
-- 再按下面写的修改整理；内置数据同样从那次导出里整理。差价合约的表（行情、订单、持仓、隔夜费、追保、风控、调余额）全部不建。
-- 已合并的迁移脚本不再修改；改表、改内置数据用新的迁移脚本。
-- 本文件：管理员、角色、角色权限、菜单顺序、会话、审计日志，以及内置的超级管理员角色（原样）。
-- 删掉 admin_balance_adjust（调余额改为结算补偿流程，S7）。

CREATE TABLE `admin_user` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(40) NOT NULL,
  `display_name` varchar(60) NOT NULL,
  `email` varchar(254) NOT NULL,
  `password_hash` varchar(100) NOT NULL COMMENT 'BCrypt',
  `status` varchar(10) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED',
  `must_change_password` tinyint(1) NOT NULL DEFAULT '1' COMMENT '首次登录、重置密码后必须先改密码',
  `failed_count` int NOT NULL DEFAULT '0' COMMENT '连续登录失败次数',
  `locked_until` datetime(3) DEFAULT NULL,
  `last_login_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_admin_user_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='后台管理员';

CREATE TABLE `admin_role` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `code` varchar(40) NOT NULL,
  `name_zh` varchar(60) NOT NULL,
  `name_en` varchar(60) NOT NULL,
  `description` varchar(200) NOT NULL DEFAULT '',
  `built_in` tinyint(1) NOT NULL DEFAULT '0' COMMENT '内置角色不能删、不能改',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_admin_role_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色';

CREATE TABLE `admin_role_permission` (
  `role_id` bigint NOT NULL,
  `permission` varchar(40) NOT NULL COMMENT '权限点，清单在代码里（admin/security/Permission.java）',
  PRIMARY KEY (`role_id`,`permission`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色的权限点；超级管理员不在这里，自动拥有全部';

CREATE TABLE `admin_user_role` (
  `admin_id` bigint NOT NULL,
  `role_id` bigint NOT NULL,
  PRIMARY KEY (`admin_id`,`role_id`),
  KEY `idx_admin_user_role_role` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理员的角色';

CREATE TABLE `admin_menu` (
  `menu_key` varchar(40) NOT NULL COMMENT '菜单结构在代码里（admin/security/Menus.java），这里只存顺序和是否显示',
  `sort_order` int NOT NULL,
  `visible` tinyint(1) NOT NULL DEFAULT '1',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`menu_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='菜单的顺序和显示';

CREATE TABLE `admin_session` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `admin_id` bigint NOT NULL,
  `token_hash` char(64) NOT NULL COMMENT '令牌的 SHA-256，库里不存原值',
  `ip` varchar(45) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL,
  `last_seen_at` datetime(3) NOT NULL,
  `expires_at` datetime(3) NOT NULL COMMENT '最长有效期（8 小时）',
  `revoked_at` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_admin_session_token` (`token_hash`),
  KEY `idx_admin_session_admin` (`admin_id`,`revoked_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='后台登录会话，和客户的会话分开';

CREATE TABLE `admin_audit_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `admin_id` bigint DEFAULT NULL COMMENT '登录失败等没有管理员时为空',
  `admin_name` varchar(40) NOT NULL,
  `ip` varchar(45) DEFAULT NULL,
  `action` varchar(60) NOT NULL COMMENT '例如 admin.create、config.update',
  `target` varchar(200) NOT NULL DEFAULT '',
  `high_risk` tinyint(1) NOT NULL,
  `result` varchar(10) NOT NULL COMMENT 'SUCCESS / FAILURE',
  `reason` varchar(500) DEFAULT NULL COMMENT '高危操作的原因；失败时是错误码',
  `before_json` text COMMENT '修改前（密码等字段记成 ***）',
  `after_json` text COMMENT '修改后（密码等字段记成 ***）',
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_admin_audit_time` (`created_at`),
  KEY `idx_admin_audit_action` (`action`,`id`),
  KEY `idx_admin_audit_admin` (`admin_name`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='审计日志，只增不改';

-- 内置数据
INSERT INTO `admin_role` (`id`, `code`, `name_zh`, `name_en`, `description`, `built_in`) VALUES (1,'super_admin','超级管理员','Super admin','拥有全部权限；只有超级管理员能分配本角色',1);
