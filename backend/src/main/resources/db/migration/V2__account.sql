-- forecast 基线迁移（S1，技术方案/v1-架构方案.md 第 3.4 节，AQ-9 按建议重建基线）。
-- 来源：在空的 MySQL 8.4 上跑 复制源（staging 2583328，见 技术方案/v1-架构方案.md 第 1.1 节）的 V1～V32，用 mysqldump --no-data 导出保留下来的表，
-- 再按下面写的修改整理；内置数据同样从那次导出里整理。差价合约的表（行情、订单、持仓、隔夜费、追保、风控、调余额）全部不建。
-- 已合并的迁移脚本不再修改；改表、改内置数据用新的迁移脚本。
-- 本文件：用户、登录会话、邮箱验证码、资料、安全事件、KYC 申请（原样）。
-- 员工账户、年龄确认、设备指纹、KYC 门槛（account_device、account_kyc_gate）在 S10（第 3.3 节）。

CREATE TABLE `account_user` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `email` varchar(254) NOT NULL,
  `password_hash` varchar(100) DEFAULT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / FROZEN',
  `frozen_reason` varchar(500) DEFAULT NULL COMMENT '后台冻结时填的原因；解冻后清空',
  `last_login_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_account_user_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户';

CREATE TABLE `account_session` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `token_hash` char(64) NOT NULL,
  `ip` varchar(45) DEFAULT NULL,
  `user_agent` varchar(255) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `last_seen_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `expires_at` datetime(3) NOT NULL,
  `revoked_at` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_account_session_token` (`token_hash`),
  KEY `idx_account_session_user` (`user_id`,`revoked_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='登录会话';

CREATE TABLE `account_email_code` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `email` varchar(254) NOT NULL,
  `purpose` varchar(16) NOT NULL COMMENT 'REGISTER / RESET_PASSWORD',
  `code_hash` char(64) NOT NULL,
  `attempts` int NOT NULL DEFAULT '0',
  `expires_at` datetime(3) NOT NULL,
  `consumed_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_account_email_code` (`email`,`purpose`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='邮箱验证码';

CREATE TABLE `account_profile` (
  `user_id` bigint NOT NULL,
  `first_name` varchar(64) NOT NULL,
  `middle_name` varchar(64) NOT NULL DEFAULT '',
  `last_name` varchar(64) NOT NULL,
  `birth_date` date NOT NULL,
  `nationality` char(2) NOT NULL COMMENT 'ISO 3166-1 两位字母',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户资料';

CREATE TABLE `account_security_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `type` varchar(16) NOT NULL COMMENT 'PASSWORD_CHANGE / PASSWORD_RESET / EMAIL_CHANGE / FREEZE / UNFREEZE',
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_account_security_event` (`user_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账号安全事件（只增不改）';

CREATE TABLE `account_kyc_application` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `doc_type` varchar(16) NOT NULL COMMENT 'ID_CARD / PASSPORT',
  `doc_number` varchar(64) NOT NULL,
  `front_key` varchar(255) NOT NULL COMMENT '对象存储路径：证件正面',
  `back_key` varchar(255) NOT NULL COMMENT '对象存储路径：证件反面',
  `selfie_key` varchar(255) NOT NULL COMMENT '对象存储路径：手持证件自拍',
  `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / APPROVED / REJECTED',
  `reject_reason` varchar(500) DEFAULT NULL,
  `reviewer_id` bigint DEFAULT NULL COMMENT '审核的管理员',
  `submitted_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `reviewed_at` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_account_kyc_user` (`user_id`,`id`),
  KEY `idx_account_kyc_doc` (`doc_type`,`doc_number`),
  KEY `idx_account_kyc_status` (`status`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='KYC 申请';
