-- forecast S4：彩池模块的表（技术方案/v1-架构方案.md v1.1 第 3.3、4.2 节；开发记录/S4-下注奖池.md）。
-- 架构方案第 3.4 节原来把 pool 排在 V7，S2、S3 用了 V6～V10，S4 从 V11 编号。
-- 表名以 pool_ 开头，只由 pool 模块读写。金额 DECIMAL(24,8)（下注金额业务上只有 2 位小数）；时间 UTC、精确到毫秒。
-- 加锁顺序（第 4.2 节）：市场行共享锁（market 模块 lockForBet）→ 客户账户行排他锁（ledger）→ pool_position → pool_option_total（最后一步，锁持有最短）。
-- pool_market 只在市场状态跳转的事务里改（市场行已经是排他锁），下注事务不碰它。

CREATE TABLE `pool_bet` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_no` bigint NOT NULL COMMENT '下注编号（雪花 ID，接口里是字符串）；账本幂等键 BET : public_no',
  `user_id` bigint NOT NULL,
  `market_id` bigint NOT NULL,
  `option_id` bigint NOT NULL,
  `amount` decimal(24,8) NOT NULL COMMENT '下注金额 b（业务上最多 2 位小数）',
  `client_req_id` varchar(64) NOT NULL COMMENT '客户端生成的请求号（UUID）：同一个客户同一个请求号只下一次注，重复提交返回第一次的结果（X-3）',
  `received_at` datetime(3) NOT NULL COMMENT '服务器收到请求的时间：截止判定（< close_at）、E14 晚于实际开赛的退回（S5）都只看它',
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE 有效 / REFUNDED 已退回（取消、作废、E14、E15，S5 起）',
  `refund_reason` varchar(16) DEFAULT NULL COMMENT 'CANCELLED / VOIDED / LATE_BET / STAFF（S5 起；R8 的"有效下注本金"不算退回的）',
  `refunded_at` datetime(3) DEFAULT NULL,
  `snapshot` json NOT NULL COMMENT '下注后各选项的奖池和当时的费率（只做展示，不参与结算）',
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pool_bet_public_no` (`public_no`),
  UNIQUE KEY `uk_pool_bet_req` (`user_id`,`client_req_id`),
  KEY `idx_pool_bet_market` (`market_id`,`status`,`received_at`),
  KEY `idx_pool_bet_user` (`user_id`,`id`),
  KEY `idx_pool_bet_user_received` (`user_id`,`received_at`),
  CONSTRAINT `ck_pool_bet_amount` CHECK ((`amount` > 0)),
  CONSTRAINT `ck_pool_bet_status` CHECK ((`status` in (_utf8mb4'ACTIVE',_utf8mb4'REFUNDED'))),
  CONSTRAINT `ck_pool_bet_refund` CHECK (((`status` = _utf8mb4'ACTIVE') = (`refund_reason` is null)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='下注（每次下注一行；下注后不能撤销，RQ-2）';

CREATE TABLE `pool_position` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `market_id` bigint NOT NULL,
  `option_id` bigint NOT NULL,
  `amount` decimal(24,8) NOT NULL COMMENT '这个客户在这个选项上的有效下注合计（结算按持仓算，只舍入一次）',
  `bet_count` int NOT NULL COMMENT '合计了几笔有效下注',
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pool_position` (`market_id`,`option_id`,`user_id`),
  KEY `idx_pool_position_user` (`user_id`,`market_id`),
  CONSTRAINT `ck_pool_position_amount` CHECK (((`amount` >= 0) and (`bet_count` >= 0)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='持仓：同一客户在同一市场同一选项的下注合计';

CREATE TABLE `pool_option_total` (
  `option_id` bigint NOT NULL,
  `market_id` bigint NOT NULL,
  `option_no` bigint NOT NULL COMMENT '选项的对外编号（和 market_option.public_no 相同，上架后不变）',
  `seq` int NOT NULL COMMENT '选项顺序 1～10',
  `amount` decimal(24,8) NOT NULL DEFAULT '0.00000000' COMMENT '选项奖池 Pᵢ = 有效下注合计',
  `bettors` int NOT NULL DEFAULT '0' COMMENT '在这个选项上有持仓的客户数',
  `final_amount` decimal(24,8) DEFAULT NULL COMMENT '截止时定格的 Pᵢ；NULL = 还没截止',
  `final_bettors` int DEFAULT NULL,
  `frozen_at` datetime(3) DEFAULT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`option_id`),
  UNIQUE KEY `uk_pool_option_total_seq` (`market_id`,`seq`),
  CONSTRAINT `ck_pool_option_total_amount` CHECK (((`amount` >= 0) and (`bettors` >= 0)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='选项奖池（只有下注事务和退款事务改 amount；截止事务写 final_amount）';

CREATE TABLE `pool_market` (
  `market_id` bigint NOT NULL,
  `status` varchar(24) NOT NULL COMMENT '市场状态的副本（market 每次状态跳转在同一个事务里同步）：我的下注按"进行中 / 待结果 / 已结算 / 已退回"分组用',
  `frozen_at` datetime(3) DEFAULT NULL COMMENT '奖池定格的时间（截止）',
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`market_id`),
  KEY `idx_pool_market_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='已上架市场在彩池里的登记（上架时建，下注事务不改它）';

-- 通知 #8：下注成功（需求第 7 节：只发站内信）。文案是研发起草的初稿，PM 在 S9 定稿（和其他客户模板一起，含繁体中文）。
-- market / option 是简体题目和选项，marketEn / optionEn 是英文，amount 是下注金额，betNo 是下注编号。
INSERT INTO `notify_template` (`code`, `audience`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `variables`, `channels`, `built_in`, `enabled`) VALUES ('BET_PLACED','USER','system','下注成功','Bet placed','下注成功','Bet placed','你已在「{market}」押「{option}」{amount} USDT（下注编号 {betNo}）。下注后不能撤销；赔率随奖池变化，最终赔率在截止时确定。','You bet {amount} USDT on "{optionEn}" in "{marketEn}" (bet {betNo}). Bets cannot be cancelled. Odds change with the pool; final odds are set at the close.','market,marketEn,option,optionEn,amount,betNo','INBOX',1,1);
