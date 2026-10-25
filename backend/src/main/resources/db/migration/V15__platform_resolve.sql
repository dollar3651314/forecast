-- forecast S7：平台裁决、公示、争议、补偿（需求说明书 v1.3 第 3.2、3.6（E6、E7、E11～E14）、4.4、7 节，AC-30～AC-36；
-- 技术方案/v1-架构方案.md 第 3.3 节 resolve_record / resolve_dispute / settle_compensation*、第 5 节；开发记录/S7-平台裁决.md）。
-- resolve_* 只由 resolve 模块读写；settle_* 只由 settle 模块读写。时间一律 UTC、精确到毫秒；金额 DECIMAL(24,8)。
-- 只增不改：resolve_record（裁决记录）、resolve_attachment（存档截图）、settle_compensation_item 的金额（Mapper 里没有它们的 UPDATE / DELETE，
-- ResolveAppendOnlyTest、SettleAppendOnlyTest 扫描）。V1～V14 没有改。

-- 裁决记录（S6 建的表）加 S7 的列：提议、确认、退回、作废、争议结论都写在这里，每一步一行（需求第 4.4 节"每一步都留痕，只增不改"）。
-- kind（S7 起）：PROPOSE 提议 / RETURN 退回提议 / CONFIRM 确认（进入公示）/ VOID 作废（人工、确认作废提议、延期 / 腰斩超时的系统作废）/
--   HOLD 标记延期或腰斩（E6、E7，等待最多 48 小时）/ RELEASE 解除标记 / PUBLICITY_END 公示期满、没有争议、进入结算 /
--   DISPUTE_UPHOLD / DISPUTE_OVERTURN / DISPUTE_VOID 争议结论。S6 的 AUTO_RESULT、AUTO_FAILED 不变。
ALTER TABLE `resolve_record`
  MODIFY COLUMN `note` varchar(2000) DEFAULT NULL COMMENT '理由、说明、争议结论（公开的那部分）',
  ADD COLUMN `ref_id` bigint DEFAULT NULL COMMENT '确认、退回、作废针对的是哪一条提议（resolve_record.id）' AFTER `option_id`,
  ADD COLUMN `actual_start_at` datetime(3) DEFAULT NULL COMMENT '提议里填的实际开赛时间（E14）' AFTER `ref_id`,
  ADD COLUMN `evidence_urls` json DEFAULT NULL COMMENT '依据链接（一手官方页面），["https://…"]' AFTER `actual_start_at`,
  ADD COLUMN `attachments` json DEFAULT NULL COMMENT '存档截图（resolve_attachment 的对外编号），["…"]' AFTER `evidence_urls`;

-- 一个市场的平台裁决进度（每个需要人工裁决的市场一行）：市场进入待裁决时建（平台裁决、没有自动裁决的品类），
-- 或加密自动裁决 30 分钟后转人工时建。阶段跟着市场状态走（market 的状态跳转事务里同步），另外记提议、时限、超时提醒、E6 / E7 的等待。
-- 改动都是带条件的更新（version 或旧值），多实例、重复执行只生效一次。
CREATE TABLE `resolve_case` (
  `market_id` bigint NOT NULL,
  `origin` varchar(16) NOT NULL COMMENT 'PLATFORM 平台裁决的市场 / AUTO_FAILED 自动裁决失败转人工 / NO_AUTO 自动裁决的品类但还没有自动裁决（体育在 S8）',
  `stage` varchar(16) NOT NULL COMMENT 'OPEN 待裁决（待提议或待确认）/ PUBLICIZING 公示中 / DISPUTED 争议中 / CLOSED 已结算、已作废',
  `proposal_id` bigint DEFAULT NULL COMMENT '当前待确认的提议（resolve_record.id）；没有为空',
  `proposed_at` datetime(3) DEFAULT NULL,
  `propose_due_at` datetime(3) NOT NULL COMMENT '提议时限（需求第 4.4 节：体育、加密转人工的进入裁决台后 2 小时，时事 24 小时；参数设置 resolve.proposeMinutes.*）',
  `confirm_due_at` datetime(3) DEFAULT NULL COMMENT '确认时限（提议后 2 小时，resolve.confirmMinutes）',
  `hold_kind` varchar(16) DEFAULT NULL COMMENT 'E6 POSTPONED 延期 / E7 ABANDONED 腰斩：等待期间不能提议',
  `hold_until` datetime(3) DEFAULT NULL COMMENT '等到这个时间还没解除 → 系统作废、全额退款（E6 原定开赛后 48 小时；E7 标记后 48 小时）',
  `result_option_id` bigint DEFAULT NULL COMMENT '要结算的结果：确认的结果，争议改判后是新结果',
  `actual_start_at` datetime(3) DEFAULT NULL COMMENT '实际开赛时间（E14）',
  `resolved_by` varchar(32) DEFAULT NULL COMMENT '公开的裁决人编号：确认结果的管理员；争议改判的是处理争议的管理员',
  `confirm_record_id` bigint DEFAULT NULL,
  `confirmed_at` datetime(3) DEFAULT NULL,
  `dispute_deadline_at` datetime(3) DEFAULT NULL COMMENT '争议窗口截止（收到时间 < 它才接受争议；到了且没有争议 → 结算）',
  `dispute_due_at` datetime(3) DEFAULT NULL COMMENT '争议处理时限（第一个争议提交后 24 小时，dispute.handleMinutes）',
  `decision` varchar(16) DEFAULT NULL COMMENT '争议结论：UPHOLD 维持 / OVERTURN 改判 / VOID 无法判定（作废）',
  `decision_record_id` bigint DEFAULT NULL,
  `settle_requested_at` datetime(3) DEFAULT NULL COMMENT '已经交给结算（公示期满或争议处理完）；为空且条件满足时任务会（再）触发结算',
  `propose_alerted_at` datetime(3) DEFAULT NULL COMMENT '待提议超时的提醒已发（同一项只发一次）',
  `confirm_alerted_at` datetime(3) DEFAULT NULL,
  `dispute_alerted_at` datetime(3) DEFAULT NULL,
  `version` int NOT NULL DEFAULT '0',
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`market_id`),
  KEY `idx_resolve_case_stage` (`stage`, `propose_due_at`),
  KEY `idx_resolve_case_deadline` (`stage`, `dispute_deadline_at`),
  CONSTRAINT `ck_resolve_case_origin` CHECK ((`origin` in (_utf8mb4'PLATFORM',_utf8mb4'AUTO_FAILED',_utf8mb4'NO_AUTO'))),
  CONSTRAINT `ck_resolve_case_stage` CHECK ((`stage` in (_utf8mb4'OPEN',_utf8mb4'PUBLICIZING',_utf8mb4'DISPUTED',_utf8mb4'CLOSED'))),
  CONSTRAINT `ck_resolve_case_hold` CHECK (((`hold_kind` is null) or (`hold_kind` in (_utf8mb4'POSTPONED',_utf8mb4'ABANDONED')))),
  CONSTRAINT `ck_resolve_case_decision` CHECK (((`decision` is null) or (`decision` in (_utf8mb4'UPHOLD',_utf8mb4'OVERTURN',_utf8mb4'VOID'))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台裁决的进度（提议、时限、公示、争议）';

-- 争议（需求第 4.4 节、AC-32、AC-33）：只有在这个市场有下注（有效持仓）的客户能提交；每人每市场 1 次（唯一键）；理由必填、不超过 1,000 字；不收押金。
-- 结论公开在市场详情（不显示争议人）。status 只会从 OPEN 改一次（条件更新）。
CREATE TABLE `resolve_dispute` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_no` bigint NOT NULL COMMENT '对外编号（雪花 ID，接口里是字符串）',
  `market_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `claimed_option_id` bigint DEFAULT NULL COMMENT '客户认为应该是哪个结果；为空 = 认为无法判定、应该作废',
  `reason` varchar(1000) NOT NULL,
  `link` varchar(500) DEFAULT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN 待处理 / UPHELD 维持原结果 / OVERTURNED 改判 / VOIDED 作废 / CLOSED 市场已结束（没有单独的结论）',
  `decision_note` varchar(1000) DEFAULT NULL COMMENT '结论和理由（公开）',
  `handled_by` bigint DEFAULT NULL,
  `handled_at` datetime(3) DEFAULT NULL,
  `received_at` datetime(3) NOT NULL COMMENT '服务器收到的时间（< 争议截止时间才接受）',
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_resolve_dispute_public_no` (`public_no`),
  UNIQUE KEY `uk_resolve_dispute_user` (`market_id`, `user_id`),
  KEY `idx_resolve_dispute_status` (`status`, `id`),
  KEY `idx_resolve_dispute_user` (`user_id`, `id`),
  CONSTRAINT `ck_resolve_dispute_status` CHECK ((`status` in (_utf8mb4'OPEN',_utf8mb4'UPHELD',_utf8mb4'OVERTURNED',_utf8mb4'VOIDED',_utf8mb4'CLOSED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='争议（每人每市场 1 次）';

-- 裁决的存档截图（需求第 4.3、4.4 节"截图存档"，NF-09 永久保留）：文件放对象存储（和 KYC 证件照同一个 ObjectStorage），这里记路径和 sha256。只增不改。
CREATE TABLE `resolve_attachment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_no` bigint NOT NULL COMMENT '对外编号（雪花 ID）',
  `market_id` bigint NOT NULL,
  `storage_key` varchar(300) NOT NULL COMMENT '对象存储路径 resolve/{市场编号}/{截图编号}.png|jpg',
  `content_type` varchar(40) NOT NULL,
  `size_bytes` int NOT NULL,
  `sha256` char(64) NOT NULL,
  `uploaded_by` bigint NOT NULL,
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_resolve_attachment_public_no` (`public_no`),
  KEY `idx_resolve_attachment_market` (`market_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='裁决存档截图（只增不改）';

-- 补偿（需求第 3.6 节 E13、AC-35；RQ-8）：已结算后发现裁错，不回滚、不追回；平台出钱按正确结果补偿本应多得的人（只补不扣）。
-- 补偿 = 按正确结果算的派彩 − 实际已得派彩，只取 > 0 的（按客户合计）；正确结果是"作废"或正确选项没人押时，按全额退款算应得。
-- 每个市场只有一张补偿单（唯一键）；确认即入账（一个有权限的人，高危，原因必填）。账本：客户 COMPENSATION（可用 +，幂等键 COMP : 明细 id），
-- 平台补偿支出账户 COMPENSATION_EXPENSE（可用 −合计，幂等键 COMP_TOTAL : 补偿单 id，可以为负）。报表里和平台收入分开列。
CREATE TABLE `settle_compensation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_no` bigint NOT NULL COMMENT '补偿单编号（雪花 ID）',
  `market_id` bigint NOT NULL,
  `run_id` bigint NOT NULL COMMENT '原来的结算单（settle_run，PAYOUT）',
  `original_option_id` bigint NOT NULL COMMENT '原来结算用的结果',
  `correct_option_id` bigint DEFAULT NULL COMMENT '正确结果；为空 = 本应作废（全额退款）',
  `fee_rate` decimal(6,4) NOT NULL COMMENT '按原结算单的费率算应得派彩',
  `total_amount` decimal(24,8) NOT NULL COMMENT '补偿合计（平台补偿支出）',
  `user_count` int NOT NULL,
  `reason` varchar(500) NOT NULL COMMENT '原因（审计）',
  `status` varchar(8) NOT NULL COMMENT 'PAID 已入账',
  `created_by` bigint NOT NULL,
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_settle_comp_public_no` (`public_no`),
  UNIQUE KEY `uk_settle_comp_market` (`market_id`),
  KEY `idx_settle_comp_created` (`created_at`),
  CONSTRAINT `ck_settle_comp_amount` CHECK ((`total_amount` > 0)),
  CONSTRAINT `ck_settle_comp_status` CHECK ((`status` in (_utf8mb4'PAID')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='补偿单（E13，每个市场最多一张）';

CREATE TABLE `settle_compensation_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `compensation_id` bigint NOT NULL,
  `market_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `should_payout` decimal(24,8) NOT NULL COMMENT '按正确结果应得的派彩（或全额退款）',
  `actual_payout` decimal(24,8) NOT NULL COMMENT '实际已得的派彩',
  `amount` decimal(24,8) NOT NULL COMMENT '补偿 = 应得 − 实际（> 0）',
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_settle_comp_item` (`compensation_id`, `user_id`),
  KEY `idx_settle_comp_item_user` (`user_id`, `market_id`),
  CONSTRAINT `ck_settle_comp_item_amount` CHECK (((`amount` > 0) and (`should_payout` >= 0) and (`actual_payout` >= 0)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='补偿明细（每个客户一行，只增不改）';

-- 参数（需求第 4.4 节"各环节时限（默认，后台可改）"、第 6.2 节参数设置"争议窗口和裁决时限（按品类）"；betting_params:edit）。单位分钟。
-- 提议时限：体育、加密转人工的进入裁决台后 2 小时；时事事件结束后 24 小时。确认时限：提议后 2 小时。争议处理时限：提交后 24 小时。
-- 加密市场转人工后人工确认的结果也公示（研发口径，开发记录 S7 D-75），争议窗口按体育人工裁决的 2 小时。
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('resolve.proposeMinutes.FOOTBALL','120',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('resolve.proposeMinutes.NBA','120',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('resolve.proposeMinutes.CRYPTO_15M','120',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('resolve.proposeMinutes.CRYPTO_1H','120',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('resolve.proposeMinutes.MACRO','1440',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('resolve.confirmMinutes','120',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('dispute.handleMinutes','1440',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('dispute.windowMinutes.CRYPTO_15M','120',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('dispute.windowMinutes.CRYPTO_1H','120',NULL);

-- 已有数据（C55）：本步之前已经在待裁决、又不是加密自动裁决在处理的市场，补建进度行（新库里没有这样的市场，什么都不插）
INSERT IGNORE INTO `resolve_case` (`market_id`, `origin`, `stage`, `propose_due_at`, `version`, `created_at`, `updated_at`)
SELECT q.id, IF(q.resolution_mode = 'PLATFORM', 'PLATFORM', IF(t.status = 'MANUAL', 'AUTO_FAILED', 'NO_AUTO')), 'OPEN',
  UTC_TIMESTAMP(3) + INTERVAL 2 HOUR, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
FROM `market_question` q LEFT JOIN `resolve_auto_task` t ON t.market_id = q.id
WHERE q.status = 'PENDING_RESOLUTION' AND q.deleted_at IS NULL AND (t.market_id IS NULL OR t.status = 'MANUAL');

-- 通知（需求第 7 节 #10、#15、#16、#17；文案是研发起草的初稿，PM 在 S9 定稿，和其他客户模板一起加繁体中文）。
-- #10 我下注的市场公示结果（站内信 + 推送）：option 拟定结果，deadline 争议截止时间（UTC）。
INSERT INTO `notify_template` (`code`, `audience`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `variables`, `channels`, `built_in`, `enabled`) VALUES ('RESULT_PUBLICIZED','USER','system','市场公示结果','Result announced for review','「{market}」公示结果：{option}','"{marketEn}" proposed result: {optionEn}','你下注的市场「{market}」拟定结果是「{option}」，正在公示。\n如果你认为结果不对，可以在 {deadline} 之前到市场详情提交争议（每人一次，写明理由）。公示期满没有争议就按这个结果结算。','The proposed result of "{marketEn}", a market you bet on, is "{optionEn}" and is now open for review.\nIf you think it is wrong, you can file a dispute on the market page before {deadline} (once per person, with your reasons). If there is no dispute by then, the market will be settled with this result.','market,marketEn,option,optionEn,deadline','INBOX,PUSH',1,1);
-- #15 我提交的争议有结论（站内信 + 推送 + 邮件）：decision 维持 / 改判 / 作废（通知模块按语言翻译），option 最终结果（作废时没有，这一行不显示），note 结论和理由。
INSERT INTO `notify_template` (`code`, `audience`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `variables`, `channels`, `built_in`, `enabled`) VALUES ('DISPUTE_DECIDED','USER','system','争议有结论','Dispute decided','你对「{market}」的争议有结论了','Your dispute on "{marketEn}" has been decided','你对「{market}」提交的争议已处理：{decision}。\n最终结果：{option}\n结论和理由：{note}','Your dispute on "{marketEn}" has been decided: {decision}.\nFinal result: {optionEn}\nConclusion: {note}','market,marketEn,decision,option,optionEn,note','INBOX,PUSH,EMAIL',1,1);
-- #16 改判（站内信 + 推送）：发给这个市场所有有下注的客户；oldOption 原来公示的结果，option 改判后的结果。
INSERT INTO `notify_template` (`code`, `audience`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `variables`, `channels`, `built_in`, `enabled`) VALUES ('RESULT_OVERTURNED','USER','system','争议后改判','Result changed after a dispute','「{market}」争议后改判为「{option}」','"{marketEn}" result changed to "{optionEn}"','你下注的市场「{market}」公示的结果「{oldOption}」经争议处理后改判为「{option}」，将按新结果结算，结算后另有通知。','After a dispute, the result of "{marketEn}", a market you bet on, has been changed from "{oldOptionEn}" to "{optionEn}". The market will be settled with the new result; you will get a separate settlement notice.','market,marketEn,option,optionEn,oldOption,oldOptionEn','INBOX,PUSH',1,1);
-- #17 补偿到账（站内信 + 推送 + 邮件）：amount 补偿金额，option 正确结果（本应作废时为"作废"，通知模块翻译 VOID）。
INSERT INTO `notify_template` (`code`, `audience`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `variables`, `channels`, `built_in`, `enabled`) VALUES ('COMPENSATION_PAID','USER','system','补偿到账','Compensation credited','「{market}」的补偿 {amount} USDT 已到账','Compensation of {amount} USDT for "{marketEn}" credited','我们发现「{market}」结算时的结果有误，正确结果应为「{option}」。平台已按正确结果补偿你 {amount} USDT，已到可用余额。已经派出的钱不会追回。','We found that "{marketEn}" was settled with a wrong result; the correct result is "{optionEn}". The platform has compensated you {amount} USDT based on the correct result, credited to your available balance. Payouts already made will not be taken back.','market,marketEn,option,optionEn,amount','INBOX,PUSH,EMAIL',1,1);
-- 发给管理员（只发邮件，收件人 FC_RISK_ALERT_EMAILS，SQ-14 的口径；按角色发在 S9）。
INSERT INTO `notify_template` (`code`, `audience`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `variables`, `channels`, `built_in`, `enabled`) VALUES ('RESOLVE_PROPOSED','STAFF',NULL,'裁决提议待确认','Proposed result awaiting confirmation','市场 {marketNo} 有提议待确认','Market {marketNo}: proposed result awaiting confirmation','市场 {marketNo}（{market}）有新的裁决提议：{option}。\n提议人编号：{proposer}；请在 {dueAt} 之前到后台"裁决台 → 待确认"确认或退回。','A result has been proposed for market {marketNo} ({marketEn}): {optionEn}.\nProposed by admin {proposer}. Please confirm or return it in the admin console (Resolution → Awaiting confirmation) before {dueAt}.','marketNo,market,marketEn,option,optionEn,proposer,dueAt','EMAIL',1,1);
INSERT INTO `notify_template` (`code`, `audience`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `variables`, `channels`, `built_in`, `enabled`) VALUES ('RESOLVE_OVERDUE','STAFF',NULL,'裁决超时','Resolution overdue','市场 {marketNo} {what}超时','Market {marketNo}: {what} overdue','市场 {marketNo}（{market}）的{what}已超过时限（{dueAt}），裁决台里已标红。请尽快处理。','The {what} for market {marketNo} ({marketEn}) is past its deadline ({dueAt}) and is flagged in the resolution console. Please handle it as soon as possible.','marketNo,market,marketEn,what,dueAt','EMAIL',1,1);
INSERT INTO `notify_template` (`code`, `audience`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `variables`, `channels`, `built_in`, `enabled`) VALUES ('DISPUTE_NEW','STAFF',NULL,'新的争议','New dispute','市场 {marketNo} 有新的争议','Market {marketNo}: new dispute','市场 {marketNo}（{market}）收到新的争议（现在共 {count} 个）。\n请在 {dueAt} 之前到后台"公示与争议"处理（维持 / 改判 / 作废），处理完直接结算。','Market {marketNo} ({marketEn}) received a new dispute ({count} in total).\nPlease handle it in the admin console (Review and disputes) before {dueAt}: uphold, change the result, or void. The market is settled right after.','marketNo,market,marketEn,count,dueAt','EMAIL',1,1);
