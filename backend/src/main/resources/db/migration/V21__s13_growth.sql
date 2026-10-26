-- forecast S13：推广功能第一批（需求说明书 v1.3.2 第 1.5 节 MF-1、MF-3、MF-4、MF-6、MF-10；第 4.2 节；第 5.2 节 P1、P2、P8、P11、P12、P14；
-- 第 6.2 节；NF-14；AC-80～AC-85、AC-88、AC-94；开发记录/S13-推广功能1.md）。V1～V20 已合并，不再修改；新项目没有已有数据（需求第 14 节）。
--
-- 1. market_question.today_slate：市场的"列入今日场次"标记（MF-3）。加密模板按场次表生成的自动带上；人工上架的由上架的人勾选（编辑草稿时也能勾）。
--    客户端首页顶部"今日场次"、后台"今日场次"筛选和仪表盘用它（第 4.2 节）。
-- 2. market_question.ended_at：进入已结算 / 已取消 / 已作废的时间（MF-6 开奖记录、周报按它归到哪一周；状态跳转的同一事务写，之后不改）。
--    已有的终点市场按状态历史补上。
-- 3. 参数"同时开放的市场数上限" market.maxOpen（默认 2，0 = 不限；参数设置页"场次"一组，betting_params:edit）。
-- 4. account_attribution：客户的来源（MF-1，只记来源、不带任何奖励，MQ-5）：首次来源（第一次带参数访问时的值）和注册来源（注册时的值），
--    src 渠道、cmp 活动、ref 分享人或渠道编号、start（Telegram 机器人深链参数的原文）。只有注册时带了来源的客户才有一行；客户自己看不到。
-- 5. growth_visit：带来源参数的访问（来源统计的"访问"一列）。按"访问者（设备号的 sha256）+ 来源 + UTC 日期"去重，同一个人同一天同一个来源只记一次。
--    表名以 growth_ 开头，只由 growth 模块读写。
-- 6. 菜单"来源统计"（需求第 6.1 节，权限点 growth:view）；和 Menus.java、web/admin/src/config/menu.tsx 一致。

ALTER TABLE `market_question`
  ADD COLUMN `today_slate` tinyint(1) NOT NULL DEFAULT '0' COMMENT '列入今日场次（MF-3）：加密场次表生成的自动为 1；人工上架时勾选' AFTER `autogen_id`,
  ADD COLUMN `ended_at` datetime(3) DEFAULT NULL COMMENT '进入已结算 / 已取消 / 已作废的时间（MF-6 开奖记录、周报；跳转时写，之后不改）' AFTER `today_slate`,
  ADD KEY `idx_market_slate` (`today_slate`, `event_start_at`),
  ADD KEY `idx_market_ended` (`status`, `ended_at`);

UPDATE `market_question` q
  JOIN (SELECT `market_id`, MAX(`created_at`) AS `t` FROM `market_status_log`
        WHERE `to_status` IN ('SETTLED', 'CANCELLED', 'VOIDED') GROUP BY `market_id`) l ON l.`market_id` = q.`id`
SET q.`ended_at` = l.`t`
WHERE q.`status` IN ('SETTLED', 'CANCELLED', 'VOIDED') AND q.`ended_at` IS NULL;

UPDATE `market_question` q JOIN `market_autogen` a ON a.`id` = q.`autogen_id`
SET q.`today_slate` = 1
WHERE a.`mode` = 'SCHEDULE';

INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('market.maxOpen', '2', NULL);

CREATE TABLE `account_attribution` (
  `user_id` bigint NOT NULL,
  `first_src` varchar(64) DEFAULT NULL COMMENT '首次来源：渠道（第一次带参数访问时的值，浏览器里保存 30 天）',
  `first_cmp` varchar(64) DEFAULT NULL COMMENT '首次来源：活动',
  `first_ref` varchar(64) DEFAULT NULL COMMENT '首次来源：分享人（客户编号）或渠道编号',
  `first_start` varchar(64) DEFAULT NULL COMMENT '首次来源：Telegram 机器人深链 start 参数的原文',
  `first_at` datetime(3) DEFAULT NULL COMMENT '首次带参数访问的时间（浏览器记的，按服务器时间限定在注册前 31 天内）',
  `reg_src` varchar(64) DEFAULT NULL COMMENT '注册来源：注册时浏览器里最近一次的来源',
  `reg_cmp` varchar(64) DEFAULT NULL,
  `reg_ref` varchar(64) DEFAULT NULL,
  `reg_start` varchar(64) DEFAULT NULL,
  `reg_seen_at` datetime(3) DEFAULT NULL COMMENT '最近一次带参数访问的时间',
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`user_id`),
  KEY `idx_account_attribution_first` (`first_src`, `first_cmp`),
  KEY `idx_account_attribution_ref` (`first_ref`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户的来源（MF-1，只用于来源统计，不给任何人奖励）';

CREATE TABLE `growth_visit` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `visit_day` date NOT NULL COMMENT 'UTC 日期',
  `visitor` char(64) NOT NULL COMMENT '访问者：浏览器设备号的 sha256（不存设备号原文）',
  `src` varchar(64) NOT NULL DEFAULT '',
  `cmp` varchar(64) NOT NULL DEFAULT '',
  `ref` varchar(64) NOT NULL DEFAULT '',
  `start_param` varchar(64) NOT NULL DEFAULT '' COMMENT 'Telegram 机器人深链 start 参数的原文',
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_growth_visit` (`visit_day`, `visitor`, `src`, `cmp`, `ref`),
  KEY `idx_growth_visit_src` (`src`, `visit_day`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='带来源参数的访问（MF-1 来源统计的"访问"；同一访问者同一天同一来源一行）';

INSERT IGNORE INTO `admin_menu` (`menu_key`, `sort_order`, `visible`) VALUES ('growth', 55, 1);
