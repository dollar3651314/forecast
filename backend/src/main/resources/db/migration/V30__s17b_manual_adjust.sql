-- forecast S17B：人工调账（生产可用；需求说明书 v1.5 第 21 节，"Amos 2026-10-27：A、B 两个都要"；开发记录/S17-测试入金和人工调账.md 第 2 节）。
--
-- 1. 平台账户"人工调账"（kind = PLATFORM_MANUAL_ADJUST）：人工加款、扣款的对手方。加款时它 −x、扣款时它 +x，余额可正可负（= 累计扣款 − 累计加款），
--    账本保持平衡。ck_ledger_account_kind、ck_ledger_account_nonneg 两个约束加上这个种类（V6、V29 的写法：先删后加）。
-- 2. 人工调账单 ledger_adjust：一次调整一行，只增不改（LedgerAdjustAppendOnlyTest）。方向 CREDIT（加款）/ DEBIT（扣款）；
--    类别 DEPOSIT_MISSING（充值未到账补入，只能加款）/ WRONG_CREDIT（误入账扣回，只能扣款）/ OTHER（其他）；参考号选填；
--    请求号 request_id 唯一（后台每次打开确认框生成，防重复点击）；over_limit 标记"超过单笔上限、操作人再勾了一次确认"。
-- 3. 参数：adjust.manualMax（人工调账单笔上限，默认 10,000 USDT，SQ-57）；risk.manualCredit.dayAmount（同一客户 24 小时内人工加款累计告警阈值，
--    默认 10,000 USDT，SQ-59）。都在参数设置页（betting_params:edit）。
-- 4. 客户通知模板 BALANCE_ADJUSTED（需求第 7 节 #24）：站内信 + 邮件，三种语言（繁体变量先取 dirTw、categoryTw，Render）。
-- 5. 后台菜单"资金 → 人工调账记录"（menu_key adjustments，排在对账后面）；风控扫描的进度行 manualCredits。

ALTER TABLE `ledger_account`
  DROP CHECK `ck_ledger_account_kind`;

ALTER TABLE `ledger_account`
  DROP CHECK `ck_ledger_account_nonneg`;

ALTER TABLE `ledger_account`
  MODIFY COLUMN `kind` varchar(24) NOT NULL DEFAULT 'USER'
    COMMENT 'USER（客户）/ PLATFORM_REVENUE（平台收入）/ PLATFORM_COMPENSATION（平台补偿支出）/ PLATFORM_TEST_CREDIT（测试入金，S17A）/ PLATFORM_MANUAL_ADJUST（人工调账，S17B）',
  ADD CONSTRAINT `ck_ledger_account_kind` CHECK (((`kind` = 'USER') and (`user_id` is not null))
    or ((`kind` in ('PLATFORM_REVENUE', 'PLATFORM_COMPENSATION', 'PLATFORM_TEST_CREDIT', 'PLATFORM_MANUAL_ADJUST')) and (`user_id` is null))),
  ADD CONSTRAINT `ck_ledger_account_nonneg` CHECK (((`available` >= 0) or (`kind` in ('PLATFORM_COMPENSATION', 'PLATFORM_TEST_CREDIT', 'PLATFORM_MANUAL_ADJUST')))
    and (`bet_locked` >= 0) and (`withdraw_frozen` >= 0)),
  COMMENT = '账户：每个客户一行，另有平台收入、平台补偿支出、测试入金、人工调账各一行';

INSERT INTO `ledger_account` (`kind`, `user_id`) VALUES ('PLATFORM_MANUAL_ADJUST', NULL);

CREATE TABLE `ledger_adjust` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_no` bigint NOT NULL COMMENT '对外编号（雪花 ID）',
  `user_id` bigint NOT NULL COMMENT '客户',
  `direction` varchar(8) NOT NULL COMMENT 'CREDIT（人工加款）/ DEBIT（人工扣款）',
  `category` varchar(24) NOT NULL COMMENT 'DEPOSIT_MISSING（充值未到账补入）/ WRONG_CREDIT（误入账扣回）/ OTHER（其他）',
  `amount` decimal(24,8) NOT NULL COMMENT '金额（> 0；方向看 direction）',
  `available_before` decimal(24,8) NOT NULL COMMENT '调整前的可用余额',
  `available_after` decimal(24,8) NOT NULL COMMENT '调整后的可用余额',
  `ref_no` varchar(128) DEFAULT NULL COMMENT '参考号（链上交易哈希或工单号，选填）',
  `reason` varchar(500) NOT NULL COMMENT '原因说明（必填）',
  `over_limit` tinyint(1) NOT NULL DEFAULT 0 COMMENT '超过单笔上限（操作人再勾了一次"我确认超过单笔上限"）',
  `request_id` varchar(64) NOT NULL COMMENT '请求号（后台每次打开确认框生成，防重复点击）',
  `created_by` bigint NOT NULL COMMENT '操作的管理员 id',
  `created_at` datetime(3) NOT NULL COMMENT '调整时间（UTC）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ledger_adjust_no` (`public_no`),
  UNIQUE KEY `uk_ledger_adjust_req` (`request_id`),
  KEY `idx_ledger_adjust_user` (`user_id`, `created_at`),
  KEY `idx_ledger_adjust_time` (`created_at`),
  CONSTRAINT `ck_ledger_adjust_amount` CHECK (`amount` > 0),
  CONSTRAINT `ck_ledger_adjust_direction` CHECK ((`direction` in (_utf8mb4'CREDIT', _utf8mb4'DEBIT'))),
  CONSTRAINT `ck_ledger_adjust_category` CHECK (((`direction` = _utf8mb4'CREDIT') and (`category` in (_utf8mb4'DEPOSIT_MISSING', _utf8mb4'OTHER')))
    or ((`direction` = _utf8mb4'DEBIT') and (`category` in (_utf8mb4'WRONG_CREDIT', _utf8mb4'OTHER'))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='人工调账单（S17B；只增不改，改错了再做一笔反方向的）';

INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('adjust.manualMax', '10000', NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('risk.manualCredit.dayAmount', '10000', NULL);

INSERT INTO `notify_template` (`code`, `audience`, `req_no`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `title_tw`, `body_tw`, `variables`, `channels`, `built_in`, `enabled`) VALUES ('BALANCE_ADJUSTED', 'USER', '#24', 'system', '余额人工调整', 'Balance adjusted',
  '你的余额已调整：{dir} {amount} USDT',
  'Your balance was adjusted: {dirEn} {amount} USDT',
  '平台对你的账户做了一笔人工调整：{dir} {amount} USDT，类别：{category}。调整后可用余额 {available} USDT。明细见"资产 → 资金流水"。如有疑问请联系客服。',
  'We made a manual adjustment to your account: {dirEn} {amount} USDT, category: {categoryEn}. Available balance after the adjustment: {available} USDT. See Assets → Transactions for details. Contact support if you have questions.',
  '你的餘額已調整：{dir} {amount} USDT',
  '平台對你的帳戶做了一筆人工調整：{dir} {amount} USDT，類別：{category}。調整後可用餘額 {available} USDT。明細見「資產 → 資金流水」。如有疑問請聯絡客服。',
  'dir,dirEn,dirTw,category,categoryEn,categoryTw,amount,available,adjustNo', 'INBOX,EMAIL', 1, 1);

INSERT IGNORE INTO `admin_menu` (`menu_key`, `sort_order`, `visible`) VALUES ('adjustments', 35, 1);

INSERT INTO `riskalert_cursor` (`name`, `last_id`) VALUES ('manualCredits', 0);
