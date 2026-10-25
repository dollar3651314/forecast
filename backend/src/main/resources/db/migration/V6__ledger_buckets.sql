-- S2 账本拆分（技术方案/v1-架构方案.md 第 3.2 节、第 16 节 S2；开发记录/S2-账本拆分.md）。
-- 基线 V1～V5 已合并，不再修改；本脚本只用 ALTER 改 V1 建的 ledger_account、ledger_entry。
-- 新项目从空库开始（需求说明书第 14 节），这里仍按"已有数据"写：已有的 BALANCE 记录改名为 AVAILABLE，余额列改名后数值不变。
--
-- ledger_account：
--   ① balance → available（可用余额），新增 bet_locked（下注锁定，客户端显示"下注中"），withdraw_frozen 不变；
--   ② 新增 kind：USER（客户）/ PLATFORM_REVENUE（平台收入：抽成 + 结算尾差）/ PLATFORM_COMPENSATION（平台补偿支出）；
--      平台账户没有 user_id，每种只有一行（生成列 platform_key 上的唯一键）；
--   ③ 不能为负：三项都 ≥ 0；只有平台补偿支出账户的 available 可以为负（平台自掏的钱，余额 = −累计补偿）。
-- ledger_entry：
--   ① bucket 的值 BALANCE → AVAILABLE；新增 BET_LOCKED；
--   ② 新增 ref_market_id（和哪个市场有关：下注、派彩、抽成、退款、补偿），彩池对账按它合计（第 4.4 节、AC-39）。
-- 流水类型（entry_type）不在数据库里约束，定义和"哪种账户、哪一项、增还是减"的规则在 ledger.api.LedgerEntryType。

ALTER TABLE `ledger_account`
  DROP CHECK `ck_ledger_account_nonneg`;

ALTER TABLE `ledger_account`
  RENAME COLUMN `balance` TO `available`;

ALTER TABLE `ledger_account`
  ADD COLUMN `kind` varchar(24) NOT NULL DEFAULT 'USER' COMMENT 'USER（客户）/ PLATFORM_REVENUE（平台收入：抽成 + 尾差）/ PLATFORM_COMPENSATION（平台补偿支出）' AFTER `id`,
  MODIFY COLUMN `user_id` bigint NULL COMMENT '客户的用户 id；平台账户为空',
  MODIFY COLUMN `available` decimal(24,8) NOT NULL DEFAULT '0.00000000' COMMENT '可用余额（可以提现、下注）',
  ADD COLUMN `bet_locked` decimal(24,8) NOT NULL DEFAULT '0.00000000' COMMENT '下注锁定（客户端"下注中"）：已下注、还没结算或退回的本金' AFTER `available`,
  ADD COLUMN `platform_key` varchar(24) GENERATED ALWAYS AS (if(`user_id` is null, `kind`, NULL)) STORED COMMENT '平台账户每种只有一行（唯一键）；客户账户为空',
  ADD UNIQUE KEY `uk_ledger_account_platform` (`platform_key`),
  ADD CONSTRAINT `ck_ledger_account_kind` CHECK (((`kind` = 'USER') and (`user_id` is not null))
    or ((`kind` in ('PLATFORM_REVENUE', 'PLATFORM_COMPENSATION')) and (`user_id` is null))),
  ADD CONSTRAINT `ck_ledger_account_nonneg` CHECK (((`available` >= 0) or (`kind` = 'PLATFORM_COMPENSATION'))
    and (`bet_locked` >= 0) and (`withdraw_frozen` >= 0)),
  COMMENT = '账户：每个客户一行，另有平台收入、平台补偿支出各一行';

ALTER TABLE `ledger_entry`
  MODIFY COLUMN `bucket` varchar(16) NOT NULL COMMENT '变动的是哪一项：AVAILABLE / BET_LOCKED / WITHDRAW_FROZEN（ledger.api.Bucket）',
  ADD COLUMN `ref_market_id` bigint DEFAULT NULL COMMENT '和哪个市场有关（下注、派彩、抽成、退款、补偿）；充值、提现为空' AFTER `biz_id`,
  ADD KEY `idx_ledger_entry_market` (`ref_market_id`, `entry_type`);

UPDATE `ledger_entry` SET `bucket` = 'AVAILABLE' WHERE `bucket` = 'BALANCE';

-- 平台的两个账户（第 3.2 节）
INSERT INTO `ledger_account` (`kind`, `user_id`) VALUES ('PLATFORM_REVENUE', NULL), ('PLATFORM_COMPENSATION', NULL);
