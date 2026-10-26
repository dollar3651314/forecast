-- forecast S10：风控告警、提现自动审批 R8～R15 改造、员工账户、关联账户（需求说明书 v1.3.2 第 8 节、第 3.3、3.6 节 E15、第 6.2 节；
-- 技术方案/v1-架构方案.md 第 1.2 节 account ④ / wallet ③、第 3.3 节 riskalert；开发记录/S10-风控和自动审批.md）。
-- V1～V18 已合并，不再修改；新项目没有已有数据（需求第 14 节），新表从空开始，新配置项写默认值。
--
-- 1. account_device：注册、登录时记下设备号（客户端生成的随机号，只存 SHA-256）和 IP，给关联账户用；
-- 2. riskalert_*：关联账户的线索、关联关系、告警、扫描进度；
-- 3. 参数：尾盘大额（N 分钟、X%）、新账户押中冷门（注册天数、金额、赔率）、关联的同一线索最多几个账户（参数设置页，betting_params:edit）；
-- 4. 提现自动审批 R14（关联账户对押）、R15（可疑押中）的开关和参数；
-- 5. 员工模板 RISK_ALERT（发给有 risk_alert:view 的在职管理员，SQ-19 下就是超管；没有人时发告警邮箱）；
-- 6. 菜单"风控告警"的默认顺序。

CREATE TABLE `account_device` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `kind` varchar(8) NOT NULL COMMENT 'REGISTER / LOGIN',
  `device_hash` char(64) DEFAULT NULL COMMENT '客户端设备号（浏览器本地生成的随机号，请求头 X-Device-Id）的 SHA-256；没带时为空',
  `ip` varchar(45) DEFAULT NULL COMMENT '请求的 IP（request.getRemoteAddr，Nginx 传来的真实 IP）',
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_account_device_user` (`user_id`,`id`),
  KEY `idx_account_device_hash` (`device_hash`),
  KEY `idx_account_device_ip` (`ip`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='注册、登录时的设备号和 IP（关联账户的线索），只增不改';

CREATE TABLE `riskalert_signal` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `kind` varchar(16) NOT NULL COMMENT 'DEVICE 设备号 / IP / DEPOSIT_FROM 充值来源地址 / WITHDRAW_TO 提现白名单地址',
  `value` varchar(128) NOT NULL COMMENT '设备号的哈希 / IP / 链:地址（以太坊体系小写）',
  `user_id` bigint NOT NULL,
  `first_seen_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_riskalert_signal` (`kind`,`value`,`user_id`),
  KEY `idx_riskalert_signal_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='关联账户的线索：哪个客户用过哪个设备、IP、地址';

CREATE TABLE `riskalert_link` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_a` bigint NOT NULL COMMENT '较小的客户号',
  `user_b` bigint NOT NULL COMMENT '较大的客户号',
  `reason` varchar(16) NOT NULL COMMENT 'DEVICE / IP / DEPOSIT_FROM / WITHDRAW_TO',
  `evidence` varchar(128) NOT NULL COMMENT '共同的那个值（设备号哈希、IP、链:地址）',
  `first_seen_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_riskalert_link` (`user_a`,`user_b`,`reason`),
  KEY `idx_riskalert_link_b` (`user_b`),
  CONSTRAINT `ck_riskalert_link_order` CHECK ((`user_a` < `user_b`))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='关联账户（需求第 8.1 节）：只增不删';

CREATE TABLE `riskalert_alert` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `kind` varchar(24) NOT NULL COMMENT 'LATE_LARGE_BET 尾盘大额 / LINKED_OPPOSITE 关联账户对押 / NEW_ACCOUNT_LONGSHOT 新账户押中冷门 / DEPOSIT_NO_BET 充了不玩就提',
  `user_id` bigint NOT NULL,
  `market_id` bigint DEFAULT NULL COMMENT '和哪个市场有关；充了不玩就提为空',
  `ref_key` varchar(64) NOT NULL COMMENT '同一情况只告警一次：M:市场 id 或 W:提现 id',
  `amount` decimal(24,8) DEFAULT NULL COMMENT '下注金额 / 提现金额',
  `detail` json NOT NULL COMMENT '判定时的数字（奖池、比例、赔率、关联的客户等）',
  `status` varchar(16) NOT NULL DEFAULT 'NEW' COMMENT 'NEW 新 / SEEN 已查看 / HANDLED 已处理 / FALSE_POSITIVE 误报',
  `note` varchar(500) DEFAULT NULL COMMENT '处理说明',
  `handled_by` bigint DEFAULT NULL COMMENT '最后改状态的管理员',
  `handled_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_riskalert_alert` (`kind`,`user_id`,`ref_key`),
  KEY `idx_riskalert_alert_status` (`status`,`id`),
  KEY `idx_riskalert_alert_user` (`user_id`,`created_at`),
  CONSTRAINT `ck_riskalert_alert_status` CHECK ((`status` in (_utf8mb4'NEW',_utf8mb4'SEEN',_utf8mb4'HANDLED',_utf8mb4'FALSE_POSITIVE')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='风控告警（需求第 8.1 节、第 6.2 节）';

CREATE TABLE `riskalert_cursor` (
  `name` varchar(32) NOT NULL COMMENT '扫描的来源：devices / deposits / whitelist / withdrawals / bets',
  `last_id` bigint NOT NULL DEFAULT '0' COMMENT '处理到的 id',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='风控扫描到哪里了';

INSERT INTO `riskalert_cursor` (`name`, `last_id`) VALUES ('devices', 0), ('deposits', 0), ('whitelist', 0), ('withdrawals', 0), ('bets', 0);

-- 参数（参数设置页"风控"一组，betting_params:edit；需求第 8.1 节的默认值）
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('risk.lateBet.minutes','5',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('risk.lateBet.poolPct','30',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('risk.longshot.accountDays','7',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('risk.longshot.minAmount','1000',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('risk.longshot.minOdds','5',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('risk.link.maxUsersPerKey','10',NULL);

-- 提现自动审批 R14、R15（需求第 8.2 节）；不改配置版本号（版本号只在后台保存时 +1）
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('withdraw.autoApprove.R14.enabled','true',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('withdraw.autoApprove.R14.days','30',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('withdraw.autoApprove.R14.minProfit','100',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('withdraw.autoApprove.R15.enabled','true',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('withdraw.autoApprove.R15.days','7',NULL);

-- 员工模板：风控告警（需求第 7 节"发给管理员"：风控告警 → 财务 + 超管；SQ-19 第一期只有超管）
INSERT INTO `notify_template` (`code`, `audience`, `req_no`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `title_tw`, `body_tw`, `variables`, `channels`, `staff_perms`, `built_in`, `enabled`) VALUES ('RISK_ALERT', 'STAFF', NULL, NULL, '风控告警', 'Risk alert', '风控告警：{kind}（客户 {userId}）', 'Risk alert: {kindEn} (customer {userId})', '风控规则发现一条新的告警：\n类型：{kind}\n客户：{userId}\n市场：{market}\n金额：{amount} USDT\n说明：{summary}\n请到后台"风控告警"查看，核对后标记已处理或误报。', 'A risk rule raised a new alert:\nType: {kindEn}\nCustomer: {userId}\nMarket: {market}\nAmount: {amount} USDT\nDetails: {summaryEn}\nPlease review it in the admin console (Risk alerts) and mark it handled or a false positive.', '', '', 'kind,kindEn,userId,market,amount,summary,summaryEn', 'EMAIL', 'risk_alert:view', 1, 1);

-- 菜单：风控告警（需求第 6.1 节）；和 Menus.java、web/admin/src/config/menu.tsx 一致
INSERT IGNORE INTO `admin_menu` (`menu_key`, `sort_order`, `visible`) VALUES ('risk-alerts', 95, 1);
