-- forecast S17A：测试入金（只在测试环境存在；开发记录/S17-测试入金和人工调账.md 第 1 节）。
-- 背景：测试环境的充值合约还没部署，客户充不了钱，下注到提现的整条流程测不了（Amos 2026-10-27："我需要给客户调整资金"，"A、B 两个都要"）。
--
-- 1. 平台账户"测试入金"（kind = PLATFORM_TEST_CREDIT）：测试入金的对手方，可用余额允许为负（= −累计测试入金），账本保持平衡。
--    ck_ledger_account_kind、ck_ledger_account_nonneg 两个约束加上这个种类（V6 建的约束，按 V6 的写法先删后加）。
--    生产上这一行也建（余额永远是 0）：测试入金的接口在生产不存在（FC_ENV_LABEL 不是 test 时 404）。
-- 2. 测试入金单 ledger_test_credit：一次测试入金一行，只增不改（LedgerAppendOnlyTest）。请求号 request_id 由后台每次打开确认框时生成，
--    唯一键防重复点击：同一个请求号再来一次返回第一次的结果。
-- 3. 参数 adjust.testCreditMax：测试入金的单笔上限，默认 10,000 USDT（参数设置页"余额调整"一组，betting_params:edit；SQ-56 待 Amos 确认）。

ALTER TABLE `ledger_account`
  DROP CHECK `ck_ledger_account_kind`;

ALTER TABLE `ledger_account`
  DROP CHECK `ck_ledger_account_nonneg`;

ALTER TABLE `ledger_account`
  MODIFY COLUMN `kind` varchar(24) NOT NULL DEFAULT 'USER'
    COMMENT 'USER（客户）/ PLATFORM_REVENUE（平台收入：抽成 + 尾差）/ PLATFORM_COMPENSATION（平台补偿支出）/ PLATFORM_TEST_CREDIT（测试入金，S17A）',
  ADD CONSTRAINT `ck_ledger_account_kind` CHECK (((`kind` = 'USER') and (`user_id` is not null))
    or ((`kind` in ('PLATFORM_REVENUE', 'PLATFORM_COMPENSATION', 'PLATFORM_TEST_CREDIT')) and (`user_id` is null))),
  ADD CONSTRAINT `ck_ledger_account_nonneg` CHECK (((`available` >= 0) or (`kind` in ('PLATFORM_COMPENSATION', 'PLATFORM_TEST_CREDIT')))
    and (`bet_locked` >= 0) and (`withdraw_frozen` >= 0)),
  COMMENT = '账户：每个客户一行，另有平台收入、平台补偿支出、测试入金各一行';

INSERT INTO `ledger_account` (`kind`, `user_id`) VALUES ('PLATFORM_TEST_CREDIT', NULL);

CREATE TABLE `ledger_test_credit` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_no` bigint NOT NULL COMMENT '对外编号（雪花 ID）',
  `user_id` bigint NOT NULL COMMENT '客户',
  `amount` decimal(24,8) NOT NULL COMMENT '入金金额（> 0）',
  `available_before` decimal(24,8) NOT NULL COMMENT '入金前的可用余额',
  `available_after` decimal(24,8) NOT NULL COMMENT '入金后的可用余额',
  `reason` varchar(500) NOT NULL COMMENT '原因（后台必填）',
  `request_id` varchar(64) NOT NULL COMMENT '请求号（后台每次打开确认框生成，防重复点击）',
  `created_by` bigint NOT NULL COMMENT '操作的管理员 id',
  `created_at` datetime(3) NOT NULL COMMENT '入金时间（UTC）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ledger_test_credit_no` (`public_no`),
  UNIQUE KEY `uk_ledger_test_credit_req` (`request_id`),
  KEY `idx_ledger_test_credit_user` (`user_id`, `id`),
  KEY `idx_ledger_test_credit_time` (`created_at`),
  CONSTRAINT `ck_ledger_test_credit_amount` CHECK (`amount` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='测试入金单（S17A，只在测试环境产生；只增不改）';

INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('adjust.testCreditMax', '10000', NULL);
