-- forecast S5：结算、退款、彩池对账（技术方案/v1-架构方案.md v1.1 第 3.3 节 settle 模块、第 4.3、4.4 节；开发记录/S5-结算.md）。
-- 架构方案第 3.4 节原来把 settle 排在 V8，S2～S4 用了 V6～V12，S5 从 V13 编号。补偿（settle_compensation*）是 S7 的功能，到时再建。
-- 表名以 settle_ 开头，只由 settle 模块读写。金额 DECIMAL(24,8)（业务上只有 2 位小数）；时间 UTC、精确到毫秒。
-- 三道防重复派彩（第 4.3 节）：① settle_run 唯一 (market_id, kind)；② 明细状态条件更新 PENDING → DONE；③ 账本幂等键 SETTLE : 明细 id / REFUND : 退款明细 id。
-- settle_item、settle_refund_item 的金额列写入后不改（Mapper 里没有改金额的语句，SettleAppendOnlyTest 扫描）。

CREATE TABLE `settle_run` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_no` bigint NOT NULL COMMENT '结算单编号（雪花 ID，接口里是字符串）',
  `market_id` bigint NOT NULL,
  `kind` varchar(8) NOT NULL COMMENT 'PAYOUT 结算派彩 / REFUND 取消、作废的全额退款',
  `reason` varchar(500) DEFAULT NULL COMMENT 'REFUND：退款原因（CANCELLED 取消时的原因、NOT_ENOUGH_OPTIONS、NO_WINNER、NO_POOL、人工作废的原因）',
  `winning_option_id` bigint DEFAULT NULL COMMENT 'PAYOUT：赢的选项',
  `fee_rate` decimal(6,4) DEFAULT NULL COMMENT 'PAYOUT：结算时的费率（结算开始后不能再改，需求第 3.8 节）',
  `pool_total` decimal(24,8) NOT NULL COMMENT 'T（PAYOUT：E14、E15 退回之后的奖池；REFUND：要退的有效下注合计）',
  `winner_pool` decimal(24,8) DEFAULT NULL COMMENT 'P_W',
  `loser_pool` decimal(24,8) DEFAULT NULL COMMENT 'L = T − P_W',
  `sum_gross` decimal(24,8) DEFAULT NULL COMMENT 'ΣG',
  `sum_fee` decimal(24,8) DEFAULT NULL COMMENT 'ΣF（平台抽成收入）',
  `dust` decimal(24,8) DEFAULT NULL COMMENT '尾差 D = L − ΣG（归平台）',
  `sum_payout` decimal(24,8) NOT NULL DEFAULT '0.00000000' COMMENT 'PAYOUT：ΣPay；REFUND：Σ退款',
  `winner_positions` int NOT NULL DEFAULT '0',
  `loser_positions` int NOT NULL DEFAULT '0',
  `item_count` int NOT NULL DEFAULT '0' COMMENT '明细行数（PAYOUT：持仓；REFUND：下注）',
  `conservation_ok` tinyint(1) NOT NULL DEFAULT '0' COMMENT '计算时的守恒校验：ΣPay + ΣF + D = T、0 ≤ D < 0.01 × 赢方持仓数（REFUND：Σ退款 = T）',
  `status` varchar(16) NOT NULL COMMENT 'PAYING 派彩中 / DONE 完成 / ABNORMAL 结算异常（不动钱，告警）',
  `abnormal_reason` varchar(500) DEFAULT NULL,
  `batch_size` int NOT NULL COMMENT '派彩时每批的持仓数（forecast.settle.batch-size；0 = 计算和派彩在同一个事务里一次完成）',
  `resolved_by` varchar(32) DEFAULT NULL COMMENT '谁给的结果：管理员 id / SYSTEM / AUTO',
  `calculated_at` datetime(3) NOT NULL,
  `done_at` datetime(3) DEFAULT NULL,
  `version` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_settle_run_market` (`market_id`,`kind`),
  UNIQUE KEY `uk_settle_run_public_no` (`public_no`),
  KEY `idx_settle_run_status` (`status`,`id`),
  CONSTRAINT `ck_settle_run_kind` CHECK ((`kind` in (_utf8mb4'PAYOUT',_utf8mb4'REFUND'))),
  CONSTRAINT `ck_settle_run_status` CHECK ((`status` in (_utf8mb4'PAYING',_utf8mb4'DONE',_utf8mb4'ABNORMAL')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='结算单 / 退款单（每个市场每种只有一张：同一市场只能结算一次，AC-23）';

CREATE TABLE `settle_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `run_id` bigint NOT NULL,
  `market_id` bigint NOT NULL,
  `position_id` bigint NOT NULL COMMENT 'pool_position.id（同一客户同一选项的下注合计，结算按持仓算，只舍入一次）',
  `user_id` bigint NOT NULL,
  `option_id` bigint NOT NULL,
  `win` tinyint(1) NOT NULL COMMENT '1 = 赢方持仓',
  `stake` decimal(24,8) NOT NULL COMMENT 'b',
  `gross` decimal(24,8) NOT NULL COMMENT 'G = b × L ÷ P_W，向下取整到 0.01（输方 0）',
  `fee` decimal(24,8) NOT NULL COMMENT 'F = G × r，四舍五入到 0.01（输方 0）',
  `payout` decimal(24,8) NOT NULL COMMENT 'Pay = b + G − F（输方 0）',
  `status` varchar(8) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / DONE（条件更新，第二道防重复）',
  `done_at` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_settle_item` (`run_id`,`position_id`),
  KEY `idx_settle_item_run` (`run_id`,`status`,`user_id`,`id`),
  KEY `idx_settle_item_user` (`user_id`,`market_id`),
  CONSTRAINT `ck_settle_item_amounts` CHECK (((`stake` > 0) and (`gross` >= 0) and (`fee` >= 0) and (`payout` >= 0))),
  CONSTRAINT `ck_settle_item_status` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'DONE')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='结算明细：每个持仓一行，钱动之前全部算好';

CREATE TABLE `settle_refund_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `run_id` bigint DEFAULT NULL COMMENT '取消、作废的退款单；E14、E15、提前截止后的单笔退回为空（当场退）',
  `market_id` bigint NOT NULL,
  `bet_id` bigint NOT NULL COMMENT 'pool_bet.id',
  `user_id` bigint NOT NULL,
  `amount` decimal(24,8) NOT NULL COMMENT '退回金额 = 下注金额（全额，不收费）',
  `reason` varchar(16) NOT NULL COMMENT 'CANCELLED / VOIDED / LATE_BET（E14、提前截止）/ STAFF（E15）',
  `status` varchar(8) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / DONE',
  `created_at` datetime(3) NOT NULL,
  `done_at` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_settle_refund_bet` (`bet_id`),
  KEY `idx_settle_refund_run` (`run_id`,`status`,`user_id`,`id`),
  KEY `idx_settle_refund_market` (`market_id`,`user_id`),
  CONSTRAINT `ck_settle_refund_amount` CHECK ((`amount` > 0)),
  CONSTRAINT `ck_settle_refund_reason` CHECK ((`reason` in (_utf8mb4'CANCELLED',_utf8mb4'VOIDED',_utf8mb4'LATE_BET',_utf8mb4'STAFF'))),
  CONSTRAINT `ck_settle_refund_status` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'DONE')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='单笔退款（每笔下注最多退一次）';

CREATE TABLE `settle_pool_recon_issue` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `ref_key` varchar(64) NOT NULL COMMENT 'M:市场 id，或 A:账户 id:项（账本合计和余额对不上）',
  `market_id` bigint DEFAULT NULL,
  `check_date` date NOT NULL COMMENT '发现的日期（UTC）',
  `kind` varchar(24) NOT NULL COMMENT 'POOL_VS_BETS / POOL_VS_POSITIONS / POOL_VS_LEDGER / POOL_FINAL / CONSERVATION / PAYING_VS_LEDGER / REFUND_VS_LEDGER / LEDGER_ACCOUNT / RECON_OPEN',
  `source` varchar(16) NOT NULL COMMENT 'DAILY 每日对账 / SETTLE 结算前检查 / FINISH 结算收尾复核',
  `expected` decimal(24,8) NOT NULL,
  `actual` decimal(24,8) NOT NULL,
  `diff` decimal(24,8) NOT NULL COMMENT 'actual − expected',
  `detail` varchar(500) DEFAULT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN 待处理 / RESOLVED 已处理',
  `resolved_by` bigint DEFAULT NULL,
  `resolved_at` datetime(3) DEFAULT NULL,
  `note` varchar(500) DEFAULT NULL COMMENT '处理说明',
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_settle_recon_issue` (`ref_key`,`check_date`,`kind`),
  KEY `idx_settle_recon_status` (`status`,`id`),
  KEY `idx_settle_recon_market` (`market_id`,`status`),
  CONSTRAINT `ck_settle_recon_status` CHECK ((`status` in (_utf8mb4'OPEN',_utf8mb4'RESOLVED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='彩池对账差异（需求第 3.9 节；有待处理的差异时这个市场不派彩）';

-- 通知（需求第 7 节 #11～#14；文案是研发起草的初稿，PM 在 S9 定稿，和其他客户模板一起加繁体中文）。
-- #11 结算·我赢了（站内信 + 推送）：market / marketEn 题目，option / optionEn 赢的选项，stake 本金，gross 盈利，fee 抽成，feeRate 费率（百分比），payout 派彩。
INSERT INTO `notify_template` (`code`, `audience`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `variables`, `channels`, `built_in`, `enabled`) VALUES ('SETTLE_WON','USER','system','结算：我赢了','Settled: you won','「{market}」已结算：你赢了','"{marketEn}" settled: you won','「{market}」结果是「{option}」。你的派彩 {payout} USDT 已到可用余额（本金 {stake} + 盈利 {gross} − 抽成 {fee}，抽成为盈利的 {feeRate}%）。','"{marketEn}" resolved to "{optionEn}". Your payout of {payout} USDT is in your available balance (stake {stake} + profit {gross} − fee {fee}; the fee is {feeRate}% of the profit).','market,marketEn,option,optionEn,stake,gross,fee,feeRate,payout','INBOX,PUSH',1,1);
-- #12 结算·我输了（站内信 + 推送）：stake 输掉的本金。
INSERT INTO `notify_template` (`code`, `audience`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `variables`, `channels`, `built_in`, `enabled`) VALUES ('SETTLE_LOST','USER','system','结算：我输了','Settled: you lost','「{market}」已结算','"{marketEn}" settled','「{market}」结果是「{option}」，你押的选项没有赢，本金 {stake} USDT 进入了赢方的奖池。','"{marketEn}" resolved to "{optionEn}". Your option did not win; your stake of {stake} USDT went to the winning side of the pool.','market,marketEn,option,optionEn,stake','INBOX,PUSH',1,1);
-- #13 市场取消 / 作废，下注已退回（站内信 + 推送 + 邮件）：amount 退回合计，refundKind 取消 / 作废，refundReason 原因代码（通知模块按语言翻译；取消、人工作废时是运营填的原因，原样显示）。
INSERT INTO `notify_template` (`code`, `audience`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `variables`, `channels`, `built_in`, `enabled`) VALUES ('MARKET_REFUNDED','USER','system','市场取消或作废，下注已退回','Market cancelled or voided, bets refunded','「{market}」{refundKind}，下注已全额退回','"{marketEn}" {refundKind}; your bets were refunded','「{market}」{refundKind}，你在这个市场的下注 {amount} USDT 已全额退回到可用余额，不收任何费用。\n原因：{refundReason}','"{marketEn}" {refundKind}. Your bets of {amount} USDT in this market have been fully refunded to your available balance, with no fees.\nReason: {refundReason}','market,marketEn,refundKind,amount,refundReason','INBOX,PUSH,EMAIL',1,1);
-- #14 晚于开赛的下注被退回（E14；提前截止后在新截止时间之后收到的下注同样用它）：amount 退回合计，at 实际开赛 / 新截止时间（UTC）。
INSERT INTO `notify_template` (`code`, `audience`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `variables`, `channels`, `built_in`, `enabled`) VALUES ('LATE_BET_REFUNDED','USER','system','晚于开赛的下注已退回','Late bets refunded','「{market}」有下注已退回','Bets refunded in "{marketEn}"','「{market}」的实际开赛（或截止）时间是 {at}，你在这之后下的 {amount} USDT 不参与结算，已全额退回到可用余额。','The actual start (or close) time of "{marketEn}" was {at}. Your bets of {amount} USDT placed after that do not take part in the settlement and have been fully refunded to your available balance.','market,marketEn,amount,at','INBOX,PUSH,EMAIL',1,1);

-- 发给管理员（只发邮件，收件人 FC_RISK_ALERT_EMAILS；按权限点取收件人在 S9）。
INSERT INTO `notify_template` (`code`, `audience`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `variables`, `channels`, `built_in`, `enabled`) VALUES ('SETTLE_ABNORMAL','STAFF',NULL,'结算异常','Settlement abnormal','市场 {marketNo} 结算异常，已停止派彩','Market {marketNo}: settlement abnormal, payouts stopped','市场 {marketNo}（{market}）的{what}没有通过校验，已停止派彩，市场停在"结算异常"。\n原因：{reason}\n请在后台"结算与补偿 → 结算异常"查看，核对后处理。','The {whatEn} of market {marketNo} ({marketEn}) failed its check. Payouts are stopped and the market is held as "settlement abnormal".\nReason: {reason}\nCheck it in the admin console (Settlement → Abnormal) and handle it after reconciling.','marketNo,market,marketEn,what,whatEn,reason','EMAIL',1,1);
INSERT INTO `notify_template` (`code`, `audience`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `variables`, `channels`, `built_in`, `enabled`) VALUES ('POOL_RECON_DIFF','STAFF',NULL,'彩池对账有差异','Pool reconciliation differences','彩池对账发现 {count} 个新差异','Pool reconciliation found {count} new differences','{date} 的彩池对账发现 {count} 个新差异（{markets} 个市场），这些市场暂停派彩。\n请在后台"资金 → 彩池对账"查看，核对后标记已处理。','The pool reconciliation of {date} found {count} new differences ({markets} markets). Payouts for these markets are on hold.\nCheck them in the admin console (Funds → Pool reconciliation) and mark them handled after reconciling.','date,count,markets','EMAIL',1,1);
