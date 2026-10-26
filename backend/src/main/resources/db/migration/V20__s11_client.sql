-- forecast S11：客户端页面（需求说明书 v1.3.2 第 5 节 P1～P10、第 7 节 #23、AC-50～AC-57；开发记录/S11-客户端.md）。
-- V1～V19 已合并，不再修改；新项目没有已有数据（需求第 14 节）。
--
-- 1. market_reminder：#23 开赛提醒的订阅（SQ-20：只给注册后成功登录过的客户，用账户邮箱；在市场详情订阅，在"通知偏好"里看和退订；
--    市场开放下注时由定时任务（租约 market.reminder）发一次邮件，邮件里带免登录的退订链接）；
-- 2. account_user.terms_accepted_at：注册时勾选"已年满 18 周岁并同意服务条款"的时间（需求 P8）；之前注册的账户为空；
-- 3. #23 模板 MATCH_REMINDER 的英文改用 matchEn 变量（市场题目按收件人的语言；繁体渲染时 {match} 自动先取 matchTw，Render），
--    变量表加 matchEn、matchTw；只改仍是 V17 原始文字的行
--    （模板是运营可改的数据，V8 的做法）。

CREATE TABLE `market_reminder` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `market_id` bigint NOT NULL,
  `status` varchar(12) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE 等开放 / SENT 已发 / CANCELLED 已退订 / MISSED 开放前市场已结束或错过了截止，没有发',
  `token` char(32) NOT NULL COMMENT '邮件里退订链接用的随机串（免登录退订；只能退订这一条）',
  `cancel_via` varchar(8) DEFAULT NULL COMMENT 'USER 在客户端退订 / LINK 邮件里的退订链接',
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  `sent_at` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_market_reminder_user` (`user_id`, `market_id`),
  UNIQUE KEY `uk_market_reminder_token` (`token`),
  KEY `idx_market_reminder_due` (`status`, `market_id`),
  CONSTRAINT `ck_market_reminder_status` CHECK ((`status` in (_utf8mb4'ACTIVE', _utf8mb4'SENT', _utf8mb4'CANCELLED', _utf8mb4'MISSED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='#23 开赛提醒的订阅（每个客户每个市场一行；退订后再订阅改回 ACTIVE）';

ALTER TABLE `account_user`
  ADD COLUMN `terms_accepted_at` datetime(3) DEFAULT NULL COMMENT '注册时勾选"已年满 18 周岁并同意服务条款"的时间（S11 起必填；之前注册的为空）' AFTER `last_login_at`;

UPDATE `notify_template`
SET `title_en` = 'Match reminder: {matchEn}',
    `body_en` = 'The match you subscribed to, "{matchEn}":\nBetting opens: {openAt}\nBetting closes: {closeAt}\nView: {link}\n\nYou are receiving this email because you subscribed to reminders for this match. Unsubscribe: {unsubscribe}',
    `variables` = 'match,matchEn,matchTw,openAt,closeAt,link,unsubscribe'
WHERE `code` = 'MATCH_REMINDER'
  AND `title_en` = 'Match reminder: {match}'
  AND `body_en` = 'The match you subscribed to, "{match}":\nBetting opens: {openAt}\nBetting closes: {closeAt}\nView: {link}\n\nYou are receiving this email because you subscribed to reminders for this match. Unsubscribe: {unsubscribe}';
