-- forecast 基线迁移（S1，技术方案/v1-架构方案.md 第 3.4 节，AQ-9 按建议重建基线）。
-- 来源：在空的 MySQL 8.4 上跑 复制源（staging 2583328，见 技术方案/v1-架构方案.md 第 1.1 节）的 V1～V32，用 mysqldump --no-data 导出保留下来的表，
-- 再按下面写的修改整理；内置数据同样从那次导出里整理。差价合约的表（行情、订单、持仓、隔夜费、追保、风控、调余额）全部不建。
-- 已合并的迁移脚本不再修改；改表、改内置数据用新的迁移脚本。
-- 本文件：充值地址和方案、扫块游标、nonce、充值、挂账、白名单、提现和提现事件、提现交易、入金对账、提现自动审批（规则判定和锁）、资金归集（原样）。
-- 修改：CREATE2 盐的前缀说明改为 forecast-deposit（第 1.2 节 wallet ④）。

CREATE TABLE `wallet_address` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `family` varchar(8) NOT NULL COMMENT 'ETH / TRON',
  `scheme` varchar(10) NOT NULL DEFAULT 'XPUB' COMMENT 'XPUB（xpub 派生）/ FORWARDER（BitGo 合约转发地址，v2 归集）',
  `scheme_ver` int NOT NULL DEFAULT '0' COMMENT 'FORWARDER：wallet_address_scheme.ver；XPUB 为 0',
  `address_index` int NOT NULL COMMENT '派生序号 m/44''/币种''/0''/0/序号',
  `address` varchar(64) NOT NULL COMMENT '展示用：以太坊带大小写校验，TRON 是 T 开头',
  `address_key` varchar(64) NOT NULL COMMENT '匹配链上收款地址用：以太坊小写',
  `salt` varchar(66) DEFAULT NULL COMMENT 'FORWARDER：CREATE2 的盐 keccak256("forecast-deposit" ‖ 用户ID)',
  `status` varchar(8) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE 正在用 / RETIRED 已停用（继续扫块 30 天）',
  `active_key` varchar(40) DEFAULT NULL COMMENT '正在用时 = 用户ID:体系，停用时为空',
  `retired_at` datetime(3) DEFAULT NULL COMMENT '停用时间',
  `deployed_at` datetime(3) DEFAULT NULL COMMENT 'FORWARDER：转发合约第一次部署的时间（归集时顺便部署）',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wallet_address_key` (`family`,`address_key`),
  UNIQUE KEY `uk_wallet_address_user` (`user_id`,`family`,`scheme`,`scheme_ver`),
  UNIQUE KEY `uk_wallet_address_index` (`family`,`scheme`,`scheme_ver`,`address_index`),
  UNIQUE KEY `uk_wallet_address_active` (`active_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='充值地址';

CREATE TABLE `wallet_address_scheme` (
  `ver` int NOT NULL AUTO_INCREMENT,
  `family` varchar(8) NOT NULL COMMENT 'ETH（以太坊体系；TRON 没有合约地址）',
  `scheme` varchar(10) NOT NULL COMMENT 'FORWARDER',
  `factory` varchar(42) NOT NULL COMMENT 'BitGo ForwarderFactoryV4',
  `implementation` varchar(42) NOT NULL COMMENT 'BitGo ForwarderV4 实现合约',
  `parent` varchar(42) NOT NULL COMMENT '冷钱包（Safe 多签），写在每个转发地址里',
  `fee_address` varchar(42) NOT NULL COMMENT '批量合约 SweepBatcher（转发地址的 feeAddress）',
  `params_key` varchar(200) NOT NULL COMMENT '四个地址小写拼接，同一套只登记一次',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`ver`),
  UNIQUE KEY `uk_wallet_address_scheme` (`params_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='合约充值地址的参数（每套一行，只增不删）';

CREATE TABLE `wallet_auto_approve_lock` (
  `id` tinyint NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='提现自动审批的全局锁（只有一行）';

CREATE TABLE `wallet_chain_cursor` (
  `chain` varchar(8) NOT NULL COMMENT 'ERC20 / BSC / TRC20',
  `last_block` bigint NOT NULL,
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`chain`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='扫块进度';

CREATE TABLE `wallet_chain_nonce` (
  `chain` varchar(8) NOT NULL COMMENT 'ERC20 / BSC',
  `next_nonce` bigint NOT NULL,
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`chain`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='热钱包 nonce';

CREATE TABLE `wallet_deposit` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_no` bigint NOT NULL COMMENT '对外编号：雪花 ID；V22 之前的旧数据等于 id',
  `chain` varchar(8) NOT NULL,
  `tx_hash` varchar(80) NOT NULL,
  `log_index` int NOT NULL COMMENT '同一笔交易里第几个转账事件',
  `block_number` bigint NOT NULL,
  `block_hash` varchar(80) NOT NULL COMMENT '发现时的区块哈希，入账前再核对一次（防回滚）',
  `from_address` varchar(64) NOT NULL,
  `to_address` varchar(64) NOT NULL,
  `user_id` bigint NOT NULL,
  `amount` decimal(24,8) NOT NULL COMMENT 'USDT，按精度换算、保留 8 位（多余位数舍去）',
  `status` varchar(12) NOT NULL COMMENT 'PENDING 待确认 / CREDITED 已入账 / DROPPED 已作废 / BELOW_MIN 低于最小充值金额',
  `confirmations` int NOT NULL DEFAULT '0',
  `credited_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wallet_deposit_tx` (`chain`,`tx_hash`,`log_index`),
  UNIQUE KEY `uk_wallet_deposit_public_no` (`public_no`),
  KEY `idx_wallet_deposit_status` (`chain`,`status`),
  KEY `idx_wallet_deposit_user` (`user_id`,`id`),
  KEY `idx_wallet_deposit_to` (`chain`,`to_address`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='充值记录';

CREATE TABLE `wallet_recon_issue` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `kind` varchar(32) NOT NULL COMMENT '问题类型，见数据库设计第 16 节',
  `ref_id` varchar(120) NOT NULL COMMENT '涉及的对象：充值"链:交易:序号"、提现单号、挂账号、用户号',
  `user_id` bigint DEFAULT NULL,
  `expected` decimal(24,8) DEFAULT NULL COMMENT '应该是多少',
  `actual` decimal(24,8) DEFAULT NULL COMMENT '实际是多少',
  `detail` varchar(500) DEFAULT NULL,
  `status` varchar(12) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN 待处理 / RESOLVED 已处理',
  `resolved_by` bigint DEFAULT NULL,
  `resolved_at` datetime(3) DEFAULT NULL,
  `note` varchar(500) DEFAULT NULL COMMENT '处理说明',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_wallet_recon_issue_status` (`status`,`id`),
  KEY `idx_wallet_recon_issue_ref` (`kind`,`ref_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='对账问题';

CREATE TABLE `wallet_suspense` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `deposit_id` bigint NOT NULL COMMENT '被回滚的充值',
  `amount` decimal(24,8) NOT NULL COMMENT '没扣到的差额（USDT）',
  `status` varchar(12) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN 待处理 / RESOLVED 已处理（S14 对账页面）',
  `note` varchar(255) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `resolved_at` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wallet_suspense_deposit` (`deposit_id`),
  KEY `idx_wallet_suspense_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='充值冲正挂账';

CREATE TABLE `wallet_sweep_batch` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_no` bigint NOT NULL COMMENT '对外编号（雪花 ID，约定 25）',
  `chain` varchar(8) NOT NULL COMMENT 'ERC20 / BSC / TRC20',
  `mode` varchar(8) NOT NULL COMMENT 'CONTRACT（以太坊、BSC：服务器发起账户调用批量合约）/ OFFLINE（TRON：离线签名，下一阶段）',
  `status` varchar(20) NOT NULL COMMENT 'CONTRACT：PENDING → SENT → CONFIRMED / PARTIAL / FAILED（DEFERRED 留给自动模式）；OFFLINE（下一阶段）：TOPPING_UP / AWAITING_SIGNATURE / SIGNED / BROADCASTING / CONFIRMED / PARTIAL / FAILED / CANCELLED / EXPIRED',
  `trigger_type` varchar(8) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL 后台手动（SW-5 第一阶段只有这个）/ AUTO 定时',
  `active_chain` varchar(8) DEFAULT NULL COMMENT '进行中（PENDING / SENT）时 = 链：同一条链同时只有一个进行中的批次',
  `scheme_ver` int DEFAULT NULL COMMENT 'CONTRACT：这一批地址的合约参数是第几套（同一批只有一套）',
  `token` varchar(64) NOT NULL COMMENT '归集的代币合约（USDT）',
  `destination` varchar(64) NOT NULL COMMENT '冷钱包（快照）',
  `executor` varchar(64) DEFAULT NULL COMMENT 'CONTRACT：批量合约地址；OFFLINE：空',
  `created_by` bigint DEFAULT NULL COMMENT '管理员；自动归集为空',
  `reason` varchar(500) DEFAULT NULL,
  `retry_of` bigint DEFAULT NULL COMMENT '重试时：原批次 id',
  `address_count` int NOT NULL,
  `expected_usdt` decimal(36,18) NOT NULL COMMENT '生成时查到的余额合计',
  `swept_usdt` decimal(36,18) NOT NULL DEFAULT '0.000000000000000000' COMMENT '链上实际转给冷钱包的合计（按 Transfer 事件）',
  `fee_paid` decimal(36,18) NOT NULL DEFAULT '0.000000000000000000' COMMENT '实际手续费（手续费币）',
  `fee_asset` varchar(8) NOT NULL COMMENT 'ETH / BNB / TRX',
  `params_json` json NOT NULL COMMENT '生成时的门槛、占比上限、金额上限、gas 价格快照',
  `tx_hash` varchar(80) DEFAULT NULL COMMENT 'CONTRACT：最终上链的批量交易',
  `block_number` bigint DEFAULT NULL COMMENT 'CONTRACT：上链的区块',
  `fail_reason` varchar(500) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL,
  `sent_at` datetime(3) DEFAULT NULL,
  `finished_at` datetime(3) DEFAULT NULL,
  `expires_at` datetime(3) DEFAULT NULL COMMENT 'OFFLINE：待签名清单的有效期（下一阶段）',
  `unsigned_sha256` char(64) DEFAULT NULL COMMENT 'OFFLINE：待签名文件的哈希（下一阶段）',
  `signed_sha256` char(64) DEFAULT NULL COMMENT 'OFFLINE：签名文件的哈希（下一阶段）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wallet_sweep_batch_no` (`public_no`),
  UNIQUE KEY `uk_wallet_sweep_batch_active` (`active_chain`),
  KEY `idx_wallet_sweep_batch_chain` (`chain`,`status`),
  KEY `idx_wallet_sweep_batch_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='资金归集批次';

CREATE TABLE `wallet_sweep_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `batch_id` bigint NOT NULL,
  `actor` varchar(64) NOT NULL COMMENT '管理员 ID 或 system',
  `text` varchar(500) NOT NULL,
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_wallet_sweep_event` (`batch_id`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='归集批次事件（只增不改）';

CREATE TABLE `wallet_sweep_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `batch_id` bigint NOT NULL,
  `item_no` int NOT NULL,
  `address_id` bigint NOT NULL COMMENT 'wallet_address.id',
  `user_id` bigint NOT NULL,
  `address` varchar(64) NOT NULL,
  `address_index` int DEFAULT NULL COMMENT 'OFFLINE：派生序号（下一阶段）',
  `salt` varchar(66) DEFAULT NULL COMMENT 'CONTRACT：转发地址的盐',
  `deploy` tinyint(1) NOT NULL DEFAULT '0' COMMENT 'CONTRACT：这次是否顺便部署转发合约',
  `balance_before` decimal(36,18) NOT NULL COMMENT '生成时查到的余额',
  `est_fee` decimal(36,18) NOT NULL DEFAULT '0.000000000000000000' COMMENT '预估手续费（手续费币）',
  `swept` decimal(36,18) DEFAULT NULL COMMENT '链上实际转给冷钱包的金额',
  `status` varchar(16) NOT NULL COMMENT 'PENDING / SWEPT / FAILED；OFFLINE 另有 TOPUP_SENT / READY / SIGNED / SIGN_REJECTED / BROADCAST / EXPIRED / CANCELLED（下一阶段）',
  `fail_reason` varchar(500) DEFAULT NULL,
  `tx_hash` varchar(80) DEFAULT NULL COMMENT 'CONTRACT：同批次的批量交易；OFFLINE：这一笔自己的交易',
  `active_key` varchar(80) DEFAULT NULL COMMENT '进行中时 = 链:地址，保证同一个地址同时只在一个批次里',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wallet_sweep_item_no` (`batch_id`,`item_no`),
  UNIQUE KEY `uk_wallet_sweep_item_active` (`active_key`),
  KEY `idx_wallet_sweep_item_address` (`address_id`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='资金归集明细';

CREATE TABLE `wallet_sweep_nonce` (
  `chain` varchar(8) NOT NULL,
  `operator` varchar(64) NOT NULL,
  `next_nonce` bigint NOT NULL,
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`chain`,`operator`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='归集发起账户的 nonce';

CREATE TABLE `wallet_sweep_outflow` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `chain` varchar(8) NOT NULL,
  `tx_hash` varchar(80) NOT NULL,
  `log_index` int NOT NULL,
  `block_number` bigint NOT NULL,
  `from_address` varchar(64) NOT NULL,
  `to_address` varchar(64) NOT NULL,
  `amount` decimal(36,18) NOT NULL,
  `address_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `kind` varchar(16) NOT NULL COMMENT 'SWEEP / UNKNOWN / DEST_MISMATCH',
  `batch_id` bigint DEFAULT NULL COMMENT 'SWEEP：对应的批次',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wallet_sweep_outflow` (`chain`,`tx_hash`,`log_index`),
  KEY `idx_wallet_sweep_outflow_kind` (`kind`,`id`),
  KEY `idx_wallet_sweep_outflow_batch` (`batch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='从充值地址转出的 USDT（归集和异常转出）';

CREATE TABLE `wallet_sweep_tx` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `batch_id` bigint NOT NULL,
  `chain` varchar(8) NOT NULL,
  `attempt` int NOT NULL,
  `nonce` bigint NOT NULL,
  `tx_hash` varchar(80) NOT NULL,
  `raw_tx` mediumtext NOT NULL,
  `gas_limit` bigint NOT NULL,
  `max_fee` decimal(36,0) NOT NULL COMMENT 'wei：以太坊 maxFeePerGas，BSC gasPrice',
  `priority_fee` decimal(36,0) DEFAULT NULL COMMENT 'wei：以太坊 maxPriorityFeePerGas',
  `status` varchar(8) NOT NULL COMMENT 'SENT 已发出（或可能已发出）/ FAILED 节点明确拒绝',
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wallet_sweep_tx_hash` (`chain`,`tx_hash`),
  KEY `idx_wallet_sweep_tx_batch` (`batch_id`,`attempt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='归集交易的发送记录';

CREATE TABLE `wallet_whitelist` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `network` varchar(8) NOT NULL COMMENT 'ERC20 / BSC / TRC20',
  `address` varchar(64) NOT NULL,
  `address_key` varchar(64) NOT NULL COMMENT '去重用：以太坊小写',
  `label` varchar(32) NOT NULL,
  `active_at` datetime(3) NOT NULL COMMENT '生效时间 = 添加时间 + 24 小时',
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wallet_whitelist` (`user_id`,`network`,`address_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='提现白名单';

CREATE TABLE `wallet_withdraw_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `withdrawal_id` bigint NOT NULL,
  `from_status` varchar(16) DEFAULT NULL,
  `to_status` varchar(16) NOT NULL,
  `actor` varchar(32) NOT NULL COMMENT 'user:<id> / admin:<id> / system',
  `note` varchar(500) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_wallet_withdraw_event` (`withdrawal_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='提现事件';

CREATE TABLE `wallet_withdraw_rule_check` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `withdrawal_id` bigint NOT NULL,
  `phase` varchar(8) NOT NULL COMMENT 'PRECHECK 提交时预判（只做参考、不占额度）/ FINAL 冷静期结束时复判（以这次为准）',
  `outcome` varchar(16) NOT NULL COMMENT 'AUTO_APPROVED / MANUAL / MASTER_OFF / ERROR（有规则无法判定，按转人工处理）',
  `master_enabled` tinyint(1) NOT NULL COMMENT '判定时总开关的状态',
  `failed_rules` varchar(64) NOT NULL DEFAULT '' COMMENT '没通过和无法判定的规则，例如 R1,R5（显示用）',
  `config_version` int NOT NULL COMMENT '判定时的规则配置版本（common_config 的 withdraw.autoApprove.version）',
  `config_json` json NOT NULL,
  `results_json` json NOT NULL,
  `decided_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_wallet_withdraw_rule_check` (`withdrawal_id`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='提现自动审批的判定记录，只增不改';

CREATE TABLE `wallet_withdraw_rule_hit` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `check_id` bigint NOT NULL,
  `withdrawal_id` bigint NOT NULL,
  `rule_code` varchar(4) NOT NULL COMMENT 'R1 … R12',
  PRIMARY KEY (`id`),
  KEY `idx_wallet_withdraw_rule_hit` (`rule_code`,`withdrawal_id`),
  KEY `idx_wallet_withdraw_rule_hit_wd` (`withdrawal_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='提现自动审批触发的规则（筛选用）';

CREATE TABLE `wallet_withdraw_tx` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `withdrawal_id` bigint NOT NULL,
  `chain` varchar(8) NOT NULL,
  `attempt` int NOT NULL COMMENT '第几次（1 开始）',
  `nonce` bigint DEFAULT NULL COMMENT '以太坊体系的 nonce；TRON 为空',
  `tx_hash` varchar(80) NOT NULL,
  `raw_tx` mediumtext NOT NULL COMMENT '签好名的交易（以太坊体系是十六进制，TRON 是 JSON）',
  `fee_price` bigint NOT NULL COMMENT '以太坊体系的 gas 价格（wei）；TRON 的手续费上限（sun）',
  `expires_at` datetime(3) DEFAULT NULL COMMENT 'TRON 交易过期时间',
  `status` varchar(12) NOT NULL COMMENT 'SENT 已发出 / FAILED 节点拒绝 / EXPIRED 已过期（TRON）',
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_wallet_withdraw_tx_hash` (`chain`,`tx_hash`),
  KEY `idx_wallet_withdraw_tx` (`withdrawal_id`,`attempt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='提现广播记录';

CREATE TABLE `wallet_withdrawal` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_no` bigint NOT NULL COMMENT '对外编号：雪花 ID；V22 之前的旧数据等于 id',
  `user_id` bigint NOT NULL,
  `network` varchar(8) NOT NULL,
  `address` varchar(64) NOT NULL,
  `amount` decimal(24,8) NOT NULL COMMENT '提现金额（冻结这么多）',
  `fee` decimal(24,8) NOT NULL COMMENT '手续费，从金额里扣；链上转出 amount − fee',
  `status` varchar(16) NOT NULL,
  `cooling_ends_at` datetime(3) NOT NULL,
  `delay_ends_at` datetime(3) DEFAULT NULL,
  `tx_hash` varchar(80) DEFAULT NULL,
  `reject_reason` varchar(500) DEFAULT NULL,
  `reviewer_id` bigint DEFAULT NULL,
  `reviewed_at` datetime(3) DEFAULT NULL,
  `review_mode` varchar(8) DEFAULT NULL COMMENT 'AUTO / MANUAL；还没审核时为空',
  `auto_approved_at` datetime(3) DEFAULT NULL COMMENT '自动通过的时间（R2 / R3 的 24 小时累计按它算）',
  `last_check_id` bigint DEFAULT NULL COMMENT '最近一次判定（wallet_withdraw_rule_check.id），列表直接取摘要',
  `completed_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wallet_withdrawal_public_no` (`public_no`),
  KEY `idx_wallet_withdrawal_user` (`user_id`,`created_at`),
  KEY `idx_wallet_withdrawal_status` (`status`),
  KEY `idx_wallet_withdrawal_auto` (`review_mode`,`auto_approved_at`),
  KEY `idx_wallet_withdrawal_user_auto` (`user_id`,`review_mode`,`auto_approved_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='提现单';

-- 内置数据
INSERT INTO `wallet_auto_approve_lock` (`id`) VALUES (1);
