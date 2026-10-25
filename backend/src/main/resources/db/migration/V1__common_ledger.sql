-- forecast 基线迁移（S1，技术方案/v1-架构方案.md 第 3.4 节，AQ-9 按建议重建基线）。
-- 来源：在空的 MySQL 8.4 上跑 复制源（staging 2583328，见 技术方案/v1-架构方案.md 第 1.1 节）的 V1～V32，用 mysqldump --no-data 导出保留下来的表，
-- 再按下面写的修改整理；内置数据同样从那次导出里整理。差价合约的表（行情、订单、持仓、隔夜费、追保、风控、调余额）全部不建。
-- 已合并的迁移脚本不再修改；改表、改内置数据用新的迁移脚本。
-- 本文件：通用任务表、系统配置（含内置的默认值）、账户和账本。
-- 修改：trading_account → ledger_account（删 order_frozen 列）、trading_ledger → ledger_entry；删掉调余额上限、追保、持仓上限三类配置。
-- 定时任务租约 common_job_lease 在 S3、平台账户和三项余额在 S2（第 3.2、3.3 节）。

CREATE TABLE `common_task` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_type` varchar(48) NOT NULL,
  `payload` json NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / RUNNING / SUCCEEDED / FAILED',
  `attempts` int NOT NULL DEFAULT '0',
  `max_attempts` int NOT NULL DEFAULT '8',
  `next_run_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `locked_at` datetime(3) DEFAULT NULL COMMENT '开始处理的时间，超时未完成会被回收',
  `locked_by` varchar(64) DEFAULT NULL,
  `last_error` varchar(1000) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_common_task_pick` (`status`,`next_run_at`),
  KEY `idx_common_task_running` (`status`,`locked_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='异步任务';

CREATE TABLE `common_config` (
  `config_key` varchar(60) NOT NULL,
  `value` varchar(1000) NOT NULL,
  `updated_by` bigint DEFAULT NULL COMMENT '最后修改的管理员；初始值为空',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统配置（后台可改），键的定义在 common/config/ConfigKey.java';

CREATE TABLE `ledger_account` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `balance` decimal(24,8) NOT NULL DEFAULT '0.00000000' COMMENT '余额（S1 没有持仓和下注锁定，就是可用余额；S2 拆成可用余额 / 下注锁定）',
  `withdraw_frozen` decimal(24,8) NOT NULL DEFAULT '0.00000000' COMMENT '提现冻结',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ledger_account_user` (`user_id`),
  CONSTRAINT `ck_ledger_account_nonneg` CHECK (((`balance` >= 0) and (`withdraw_frozen` >= 0)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账户（每个客户一行）';

CREATE TABLE `ledger_entry` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `account_id` bigint NOT NULL,
  `bucket` varchar(16) NOT NULL COMMENT '变动的是哪一项：BALANCE / WITHDRAW_FROZEN',
  `entry_type` varchar(32) NOT NULL COMMENT '业务类型，见 ledger.api.LedgerEntryType',
  `amount` decimal(24,8) NOT NULL COMMENT '变动金额，增加为正、减少为负',
  `value_after` decimal(24,8) NOT NULL COMMENT '变动后这一项的值',
  `biz_type` varchar(32) NOT NULL COMMENT '来源业务，例如 DEPOSIT、WITHDRAW',
  `biz_id` varchar(128) NOT NULL COMMENT '来源业务的单号（充值是 链:交易哈希:日志序号）',
  `remark` varchar(255) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ledger_entry_idem` (`biz_type`,`biz_id`,`entry_type`,`bucket`),
  KEY `idx_ledger_entry_account` (`account_id`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账本，只增不改';

-- 内置数据
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('admin.ipWhitelist','',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('deposit.confirmations.BSC','15',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('deposit.confirmations.ERC20','12',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('deposit.confirmations.TRC20','19',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('hotWallet.minGas.BSC','0',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('hotWallet.minGas.ERC20','0',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('hotWallet.minGas.TRC20','0',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('hotWallet.minUsdt.BSC','0',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('hotWallet.minUsdt.ERC20','0',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('hotWallet.minUsdt.TRC20','0',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('login.lockMinutes','30',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('login.lockThreshold','5',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('sweep.evmCap.perAddress','5000',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('sweep.evmCap.perBatch','20000',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('sweep.maxFeeRatioPct','3',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('sweep.operatorMin.BSC','0',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('sweep.operatorMin.ERC20','0',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('sweep.threshold.BSC','10',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('sweep.threshold.ERC20','100',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('sweep.threshold.TRC20','100',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('sweep.version','0',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('whitelist.effectiveHours','24',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('whitelist.maxAddresses','10',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('withdraw.autoApprove.enabled','false',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('withdraw.autoApprove.R1.enabled','true',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('withdraw.autoApprove.R1.maxAmount','500',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('withdraw.autoApprove.R10.enabled','true',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('withdraw.autoApprove.R10.maxProfit7d','5000',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('withdraw.autoApprove.R10.maxProfitPct','50',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('withdraw.autoApprove.R11.enabled','true',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('withdraw.autoApprove.R11.maxCount','3',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('withdraw.autoApprove.R12.enabled','true',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('withdraw.autoApprove.R13.enabled','true',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('withdraw.autoApprove.R13.maxNetProfit','0',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('withdraw.autoApprove.R2.enabled','true',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('withdraw.autoApprove.R2.maxUser24h','2000',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('withdraw.autoApprove.R3.enabled','true',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('withdraw.autoApprove.R3.maxPlatform24h','20000',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('withdraw.autoApprove.R4.enabled','true',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('withdraw.autoApprove.R5.enabled','true',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('withdraw.autoApprove.R6.enabled','true',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('withdraw.autoApprove.R6.hours','48',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('withdraw.autoApprove.R7.days','7',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('withdraw.autoApprove.R7.enabled','true',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('withdraw.autoApprove.R8.enabled','true',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('withdraw.autoApprove.R8.multiple','1',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('withdraw.autoApprove.R9.enabled','true',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('withdraw.autoApprove.version','0',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('withdraw.coolingHours','2',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('withdraw.dailyLimit','30000',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('withdraw.fee.BSC','0.5',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('withdraw.fee.ERC20','5',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('withdraw.fee.TRC20','1',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('withdraw.largeDelayHours','6',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('withdraw.largeThreshold','3000',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('withdraw.maxSingle','10000',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('withdraw.minAmount','10',NULL);
