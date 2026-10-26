-- forecast S16：上线前等候页（MF-8，和 MF-17 公开数据页是同一个页面 /data；需求说明书 v1.3.2 第 5.2 节 P13、AC-95、AC-96；
-- SQ-22 Amos 2026-10-26 按建议："等候页照样收邮箱，但只发一封'正式上线了'的通知，不发开赛提醒"；开发记录/S16-等候页和机器人.md 第 3 节）。
-- 原编号 V41；整合 4 时从 V40～V42 改成 V25～V27（只改文件名和这段注释，内容不变；当时还没有在任何环境执行过），和 S15 的 V23、V24 接成 V1～V27 连续。表名以 growth_ 开头，只由 growth 模块读写。
--
-- 1. growth_waitlist：等候页留的邮箱（订阅的人不一定是客户，和账户无关）。双重确认：留邮箱 → 发确认邮件 → 点链接里的"确认订阅"才算订阅（CONFIRMED）。
--    状态 PENDING 待确认 → CONFIRMED 已确认 → NOTIFIED 已发上线通知；任何时候都能退订（UNSUBSCRIBED，链接不用登录）。同一邮箱一行（小写）。
--    确认链接 7 天有效（confirm_sent_at 起算）；同一邮箱两次确认邮件至少隔 10 分钟、最多 5 封（confirm_sends）。IP 只存 sha256。
-- 2. growth_launch：上线通知（全站只发一次）。主键固定为 1：后台"发送上线通知"插入这一行，插入失败 = 已经发过（幂等）；
--    之后由任务（LaunchNoticeJob，租约 growth.launch）一个订阅一个事务地发：CONFIRMED → NOTIFIED 的条件更新成功了才在同一事务里写邮件任务，重复执行不会重发。
-- 3. 邮件模板：WAITLIST_CONFIRM（确认订阅）、WAITLIST_LAUNCH（正式上线了）。按邮箱发（audience ANY），三种语言，按订阅时的语言发；
--    只有这两封，不发开赛提醒（SQ-22）。品牌名上线前沿用 Quick Come（约定 17）。
-- 4. 菜单"等候页订阅"（权限点 growth:view；发送上线通知要 content:publish ★），和 Menus.java、web/admin/src/config/menu.tsx 一致。

CREATE TABLE `growth_waitlist` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `email` varchar(254) NOT NULL COMMENT '邮箱（小写）；后台只显示打码后的',
  `lang` varchar(8) NOT NULL COMMENT '订阅时页面的语言：en / zh-CN / zh-TW（邮件按它发）',
  `status` varchar(16) NOT NULL COMMENT 'PENDING 待确认 / CONFIRMED 已确认 / NOTIFIED 已发上线通知 / UNSUBSCRIBED 已退订',
  `confirm_token` char(32) NOT NULL COMMENT '确认链接的随机串（16 字节随机数的十六进制）',
  `confirm_sent_at` datetime(3) NOT NULL COMMENT '最近一次发确认邮件的时间（链接 7 天有效）',
  `confirm_sends` int NOT NULL DEFAULT '1' COMMENT '一共发了几封确认邮件（最多 5 封）',
  `unsub_token` char(32) NOT NULL COMMENT '退订链接的随机串（不用登录，只能退订这一个邮箱）',
  `src` varchar(64) DEFAULT NULL COMMENT '来源（MF-1，浏览器里记的 src / cmp / ref / start；只用于统计）',
  `cmp` varchar(64) DEFAULT NULL,
  `ref` varchar(64) DEFAULT NULL,
  `start_param` varchar(64) DEFAULT NULL,
  `ip_hash` char(64) NOT NULL COMMENT '提交时的 IP 的 sha256（不存原文）',
  `created_at` datetime(3) NOT NULL,
  `confirmed_at` datetime(3) DEFAULT NULL,
  `notified_at` datetime(3) DEFAULT NULL,
  `unsubscribed_at` datetime(3) DEFAULT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_growth_waitlist_email` (`email`),
  UNIQUE KEY `uk_growth_waitlist_confirm` (`confirm_token`),
  UNIQUE KEY `uk_growth_waitlist_unsub` (`unsub_token`),
  KEY `idx_growth_waitlist_status` (`status`, `id`),
  CONSTRAINT `ck_growth_waitlist_status` CHECK ((`status` in (_utf8mb4'PENDING', _utf8mb4'CONFIRMED', _utf8mb4'NOTIFIED', _utf8mb4'UNSUBSCRIBED'))),
  CONSTRAINT `ck_growth_waitlist_lang` CHECK ((`lang` in (_utf8mb4'en', _utf8mb4'zh-CN', _utf8mb4'zh-TW')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='上线前等候页留的邮箱（MF-8，双重确认；只发一封上线通知，SQ-22）';

CREATE TABLE `growth_launch` (
  `id` tinyint NOT NULL COMMENT '固定为 1：全站只有一次上线通知',
  `status` varchar(12) NOT NULL COMMENT 'SENDING 发送中 / DONE 发完了',
  `admin_id` bigint NOT NULL COMMENT '按下"发送上线通知"的管理员',
  `reason` varchar(500) NOT NULL COMMENT '高危操作的原因（审计里也有）',
  `recipients` int NOT NULL DEFAULT '0' COMMENT '按下时已确认的订阅数',
  `sent` int NOT NULL DEFAULT '0' COMMENT '已写成邮件任务的封数',
  `created_at` datetime(3) NOT NULL,
  `finished_at` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `ck_growth_launch_once` CHECK ((`id` = 1)),
  CONSTRAINT `ck_growth_launch_status` CHECK ((`status` in (_utf8mb4'SENDING', _utf8mb4'DONE')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='上线通知（只发一次，幂等：主键固定为 1）';

INSERT INTO `notify_template` (`code`, `audience`, `req_no`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `title_tw`, `body_tw`, `variables`, `channels`, `staff_perms`, `built_in`, `enabled`) VALUES ('WAITLIST_CONFIRM', 'ANY', NULL, NULL, '等候页：确认订阅', 'Waitlist: confirm',
  '请确认订阅 Quick Come 上线通知',
  'Confirm your Quick Come launch notice',
  '你在 Quick Come 的数据页留下了这个邮箱。请打开下面的链接，点"确认订阅"（7 天内有效）：\n{confirm}\n\n确认以后，我们只会在正式上线时给你发一封通知，不发其他邮件。\n如果不是你本人提交的，忽略这封邮件即可，不会订阅。\n不想再收到：{unsubscribe}',
  'This email address was left on the Quick Come data page. Open the link below and click "Confirm" (valid for 7 days):\n{confirm}\n\nOnce confirmed, we will send you a single email when we officially launch, and nothing else.\nIf this was not you, just ignore this email and you will not be subscribed.\nUnsubscribe: {unsubscribe}',
  '請確認訂閱 Quick Come 上線通知',
  '你在 Quick Come 的資料頁留下了這個郵箱。請打開下面的連結，點「確認訂閱」（7 天內有效）：\n{confirm}\n\n確認以後，我們只會在正式上線時給你發一封通知，不發其他郵件。\n如果不是你本人提交的，忽略這封郵件即可，不會訂閱。\n不想再收到：{unsubscribe}',
  'confirm,unsubscribe', 'EMAIL', '', 1, 1);

INSERT INTO `notify_template` (`code`, `audience`, `req_no`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `title_tw`, `body_tw`, `variables`, `channels`, `staff_perms`, `built_in`, `enabled`) VALUES ('WAITLIST_LAUNCH', 'ANY', NULL, NULL, '等候页：正式上线了', 'Waitlist: we have launched',
  'Quick Come 正式上线了',
  'Quick Come is now live',
  '你在等候页订阅了上线通知：Quick Come 正式上线了。\n今天的场次：{link}\n\n玩法：用 USDT 押注一件事的结果；赢家按押注比例分输方的奖池，平台只从赢家的盈利里抽成（默认 5%），不当对手方。截止时只有一边有人押的市场全额退款。\n这是唯一的一封上线通知，之后不会再因为等候页给你发邮件。\n不想再收到：{unsubscribe}',
  'You subscribed to our launch notice on the waitlist: Quick Come is now live.\nToday''s markets: {link}\n\nHow it works: you stake USDT on the outcome of an event; winners share the losing side''s pool in proportion to their stakes, and the platform only takes a fee (5% by default) from the winners'' profit. We never bet against you. If only one side has bets at close, everyone is refunded in full.\nThis is the only launch email; we will not email you again because of the waitlist.\nUnsubscribe: {unsubscribe}',
  'Quick Come 正式上線了',
  '你在等候頁訂閱了上線通知：Quick Come 正式上線了。\n今天的場次：{link}\n\n玩法：用 USDT 押注一件事的結果；贏家按押注比例分輸方的獎池，平台只從贏家的盈利裡抽成（預設 5%），不當對手方。截止時只有一邊有人押的市場全額退款。\n這是唯一的一封上線通知，之後不會再因為等候頁給你發郵件。\n不想再收到：{unsubscribe}',
  'link,unsubscribe', 'EMAIL', '', 1, 1);

INSERT IGNORE INTO `admin_menu` (`menu_key`, `sort_order`, `visible`) VALUES ('waitlist', 56, 1);
