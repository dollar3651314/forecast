-- forecast S16：官方 Telegram 机器人 v1（MF-7；需求说明书 v1.3.2 第 6.3 节、AC-89、AC-90；SQ-22 Amos 2026-10-26 按建议：
-- "Telegram 公开频道谁都能看，只推开奖卡片，和账户无关"；MQ-2"模板审定后自动发"；开发记录/S16-等候页和机器人.md 第 4 节）。
-- 原编号 V42；整合 4 时从 V40～V42 改成 V25～V27（除了改文件名、这段注释和第 7 项菜单顺序，内容不变；当时还没有在任何环境执行过），和 S15 的 V23、V24 接成 V1～V27 连续。表名以 growth_ 开头，只由 growth 模块读写。
-- 机器人的 token 只从环境变量 FC_TELEGRAM_BOT_TOKEN 来（Amos 自己在 BotFather 建、自己填进服务器），不进库、不进日志。
--
-- 1. growth_tg_state：机器人的全局状态（一行，主键 1）：已经处理到的 update_id（getUpdates 的 offset）、紧急停止（content:publish ★，
--    按下后所有自动发布立即停止，写审计、邮件通知超管）。
-- 2. growth_tg_target：官方频道（中文、英文各一个；chat_id 由 Amos 在后台填，例如 @quickcome_cn 或 -100…；开关默认关）。
--    连续 3 次发送失败自动关掉开关并邮件通知（需求第 6.3 节"自动停"）。
-- 3. growth_tg_chat：机器人被拉进的群（收到 my_chat_member / 群消息时自动登记）。群管理员在群里发 /broadcast_on 才开启"群播报"（只发开奖卡），
--    /broadcast_off 关闭；后台也能关。机器人被移出群时记 LEFT 并关闭播报。
-- 4. growth_tg_template：自动发布的卡片模板的审定状态。SQ-22 下频道只推开奖卡，所以只有 RESULT（开奖卡）× 中文、英文两行；
--    卡片文字就是 S14 的 content/cards.json，审定时记下文字的指纹，文字改了一个字指纹就变 → 按草稿处理、不发（需求第 6.3 节）。默认草稿。
-- 5. growth_tg_post：发布日志（每个市场每个频道 / 群最多一条，唯一键防重发）：时间、渠道、帖子链接、结果；没发的原因（模板没审定、已停止、过期、数字变了）也记。
-- 6. 员工邮件模板 TELEGRAM_ALERT（发给有 content:publish 的在职管理员，第一期就是超管）：紧急停止 / 恢复、频道自动关闭、发布失败、模板文字改了要重新审定。
-- 7. 菜单"Telegram 机器人"（权限点 content:view；改动要 content:publish ★）。整合 4 并进 S15 的"内容中心"分组，作为它的发布渠道，排在"用量和设置"后面
--    （sort_order 80；内容中心分组自己的顺序是 V24 的 57，等候页订阅 V26 的 56）。

CREATE TABLE `growth_tg_state` (
  `id` tinyint NOT NULL,
  `update_offset` bigint NOT NULL DEFAULT '0' COMMENT '下一次 getUpdates 的 offset（已处理的最大 update_id + 1）',
  `emergency_stop` tinyint(1) NOT NULL DEFAULT '0' COMMENT '紧急停止：1 = 所有自动发布停止',
  `stop_reason` varchar(500) DEFAULT NULL,
  `stop_by` bigint DEFAULT NULL,
  `stop_at` datetime(3) DEFAULT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `ck_growth_tg_state_one` CHECK ((`id` = 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Telegram 机器人的全局状态（MF-7）';

INSERT INTO `growth_tg_state` (`id`, `update_offset`, `emergency_stop`, `updated_at`) VALUES (1, 0, 0, UTC_TIMESTAMP(3));

CREATE TABLE `growth_tg_target` (
  `target_key` varchar(16) NOT NULL COMMENT 'CHANNEL_ZH 中文频道 / CHANNEL_EN 英文频道',
  `lang` varchar(8) NOT NULL COMMENT '发哪种语言的卡片：zh-CN / en（MQ-8：中文渠道发中文，英文渠道发英文）',
  `chat_id` varchar(64) DEFAULT NULL COMMENT '频道的 @用户名 或 -100 开头的数字编号（Amos 在后台填）',
  `enabled` tinyint(1) NOT NULL DEFAULT '0' COMMENT '渠道开关（默认关）',
  `enabled_at` datetime(3) DEFAULT NULL COMMENT '最近一次打开开关的时间：只发这之后结束的市场（打开时不补发以前的）',
  `fail_streak` int NOT NULL DEFAULT '0' COMMENT '连续发送失败的次数（成功清零；到 3 自动关开关）',
  `disabled_reason` varchar(200) DEFAULT NULL COMMENT '自动关闭的原因',
  `last_alert_at` datetime(3) DEFAULT NULL COMMENT '最近一次发失败提醒邮件的时间（每个渠道每小时最多一封）',
  `updated_by` bigint DEFAULT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`target_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='官方 Telegram 频道（MF-7、MQ-2）';

INSERT INTO `growth_tg_target` (`target_key`, `lang`, `chat_id`, `enabled`, `updated_at`) VALUES ('CHANNEL_ZH', 'zh-CN', NULL, 0, UTC_TIMESTAMP(3));
INSERT INTO `growth_tg_target` (`target_key`, `lang`, `chat_id`, `enabled`, `updated_at`) VALUES ('CHANNEL_EN', 'en', NULL, 0, UTC_TIMESTAMP(3));

CREATE TABLE `growth_tg_chat` (
  `chat_id` bigint NOT NULL COMMENT 'Telegram 的群编号（负数）',
  `chat_type` varchar(16) NOT NULL COMMENT 'group / supergroup',
  `title` varchar(255) NOT NULL DEFAULT '',
  `status` varchar(8) NOT NULL COMMENT 'ACTIVE 机器人在群里 / LEFT 被移出',
  `broadcast` tinyint(1) NOT NULL DEFAULT '0' COMMENT '群播报（只发开奖卡）：群管理员 /broadcast_on 开启',
  `broadcast_at` datetime(3) DEFAULT NULL COMMENT '最近一次开启播报的时间：只发这之后结束的市场',
  `lang` varchar(8) NOT NULL DEFAULT 'zh-CN' COMMENT '播报的语言：zh-CN / en',
  `start_param` varchar(64) DEFAULT NULL COMMENT '用 startgroup 深链加进群时带的参数（MF-1 来源统计）',
  `fail_streak` int NOT NULL DEFAULT '0',
  `added_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`chat_id`),
  KEY `idx_growth_tg_chat_broadcast` (`status`, `broadcast`),
  CONSTRAINT `ck_growth_tg_chat_status` CHECK ((`status` in (_utf8mb4'ACTIVE', _utf8mb4'LEFT')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='机器人所在的 Telegram 群（MF-7 群播报）';

CREATE TABLE `growth_tg_template` (
  `kind` varchar(16) NOT NULL COMMENT 'RESULT 开奖卡（SQ-22：频道只推开奖卡）',
  `lang` varchar(8) NOT NULL COMMENT 'zh-CN / en',
  `status` varchar(12) NOT NULL COMMENT 'DRAFT 草稿 / APPROVED 已审定 / DISABLED 已停用',
  `fingerprint` char(64) DEFAULT NULL COMMENT '审定时模板文字的指纹（content/cards.json；现在的指纹不同 = 文字改过，按草稿处理）',
  `approved_by` bigint DEFAULT NULL,
  `approved_at` datetime(3) DEFAULT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`kind`, `lang`),
  CONSTRAINT `ck_growth_tg_template_status` CHECK ((`status` in (_utf8mb4'DRAFT', _utf8mb4'APPROVED', _utf8mb4'DISABLED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='自动发布的卡片模板审定（MQ-2，默认草稿）';

INSERT INTO `growth_tg_template` (`kind`, `lang`, `status`, `updated_at`) VALUES ('RESULT', 'zh-CN', 'DRAFT', UTC_TIMESTAMP(3));
INSERT INTO `growth_tg_template` (`kind`, `lang`, `status`, `updated_at`) VALUES ('RESULT', 'en', 'DRAFT', UTC_TIMESTAMP(3));

CREATE TABLE `growth_tg_post` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `market_no` bigint NOT NULL COMMENT '市场的对外编号',
  `kind` varchar(16) NOT NULL COMMENT 'RESULT 开奖卡',
  `target` varchar(32) NOT NULL COMMENT 'CHANNEL_ZH / CHANNEL_EN / G:群编号',
  `chat_id` varchar(64) NOT NULL,
  `lang` varchar(8) NOT NULL,
  `status` varchar(12) NOT NULL COMMENT 'PENDING 待发 / SENT 已发 / FAILED 失败 / SKIPPED 没发（原因见 note）/ STOPPED 紧急停止中没发',
  `attempts` int NOT NULL DEFAULT '0' COMMENT '已经尝试了几次（最多 3 次）',
  `next_attempt_at` datetime(3) NOT NULL,
  `message_id` bigint DEFAULT NULL,
  `post_url` varchar(255) DEFAULT NULL COMMENT '帖子链接（频道是公开的 @用户名时才有）',
  `note` varchar(500) DEFAULT NULL COMMENT '没发 / 失败的原因',
  `created_at` datetime(3) NOT NULL,
  `sent_at` datetime(3) DEFAULT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_growth_tg_post` (`market_no`, `kind`, `target`),
  KEY `idx_growth_tg_post_due` (`status`, `next_attempt_at`),
  CONSTRAINT `ck_growth_tg_post_status` CHECK ((`status` in (_utf8mb4'PENDING', _utf8mb4'SENT', _utf8mb4'FAILED', _utf8mb4'SKIPPED', _utf8mb4'STOPPED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Telegram 发布日志（每个市场每个频道 / 群最多一条）';

INSERT INTO `notify_template` (`code`, `audience`, `req_no`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `title_tw`, `body_tw`, `variables`, `channels`, `staff_perms`, `built_in`, `enabled`) VALUES ('TELEGRAM_ALERT', 'STAFF', NULL, NULL, 'Telegram 机器人提醒', 'Telegram bot notice',
  'Telegram 机器人：{event}',
  'Telegram bot: {eventEn}',
  '{event}\n说明：{detail}\n时间（UTC）：{at}\n请到后台"Telegram 机器人"查看。',
  '{eventEn}\nDetail: {detailEn}\nAt (UTC): {at}\nPlease check "Telegram bot" in the admin console.',
  '', '', 'event,eventEn,detail,detailEn,at', 'EMAIL', 'content:publish', 1, 1);

INSERT IGNORE INTO `admin_menu` (`menu_key`, `sort_order`, `visible`) VALUES ('telegram', 80, 1);
