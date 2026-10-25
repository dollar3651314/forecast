-- forecast S3：市场模块的表和定时任务租约（技术方案/v1-架构方案.md v1.1 第 3.3、5 节；开发记录/S3-市场.md）。
-- 架构方案第 3.4 节原来把 market 排在 V6，S2 用了 V6～V8，S3 从 V9 编号。
-- 表名以 market_ 开头，只由 market 模块读写；market_status_log、market_clarification、market_fee_change 只增不改（Mapper 里没有它们的 UPDATE / DELETE，MarketAppendOnlyTest 检查）。
-- 本步只建 S3 用到的表；加密自动生成模板 market_autogen、规则文本模板 market_template（S6）、赛程缓存 market_fixture（S8）随各自的步骤加。
-- 时间一律 UTC（连接的 time_zone 是 +00:00），精确到毫秒；金额 DECIMAL(24,8)；费率 DECIMAL(6,4)（0.0500 = 5%）。

CREATE TABLE `market_question` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_no` bigint NOT NULL COMMENT '对外编号（雪花 ID，接口里是字符串）',
  `category` varchar(24) NOT NULL COMMENT 'FOOTBALL / NBA / CRYPTO_15M / CRYPTO_1H / MACRO',
  `title_en` varchar(300) NOT NULL DEFAULT '',
  `title_zh_cn` varchar(300) NOT NULL DEFAULT '',
  `title_zh_tw` varchar(300) NOT NULL DEFAULT '',
  `rules_en` text NOT NULL COMMENT '判定条件、取消作废条件、数据修正（上架后只读）',
  `rules_zh_cn` text NOT NULL,
  `rules_zh_tw` text NOT NULL,
  `resolution_mode` varchar(16) DEFAULT NULL COMMENT 'AUTO 自动裁决 / PLATFORM 平台裁决；草稿可以为空，上架前必填',
  `source_primary` varchar(300) NOT NULL DEFAULT '' COMMENT '裁决来源：首选（规则区显示的文字）',
  `source_backup` varchar(300) NOT NULL DEFAULT '' COMMENT '裁决来源：备用',
  `source_ref` json DEFAULT NULL COMMENT '自动裁决用的结构化参数（S6、S8 用）',
  `status` varchar(24) NOT NULL DEFAULT 'DRAFT' COMMENT '状态机 10 个状态（market.api.MarketStatus），跳转只走 17 条边（MarketStateMachine）',
  `submitted_for_listing` tinyint(1) NOT NULL DEFAULT '0' COMMENT '运营"提交上架"的标记（RQ-17：不是状态）',
  `settle_phase` varchar(16) NOT NULL DEFAULT 'NONE' COMMENT 'NONE / CALCULATED / PAYING / DONE / ABNORMAL（S5 结算用；不是 NONE 时不能改费率）',
  `open_at` datetime(3) NOT NULL COMMENT '开放下注',
  `close_at` datetime(3) NOT NULL COMMENT '截止下注：收到时间 < close_at 才接受（AC-15）；上架后只能提前',
  `event_start_at` datetime(3) NOT NULL,
  `event_end_at` datetime(3) NOT NULL,
  `actual_start_at` datetime(3) DEFAULT NULL COMMENT '裁决员填的实际开赛时间（E14，S7）',
  `fee_rate` decimal(6,4) NOT NULL COMMENT '当前费率；新建时按品类默认值带出，之后只能经"改费率"修改（fee:edit）',
  `user_cap` decimal(24,8) NOT NULL COMMENT '单客户在本市场的累计上限（按品类默认，只能调低）',
  `dispute_window_min` int DEFAULT NULL COMMENT '争议窗口分钟数（平台裁决必填）',
  `winning_option_id` bigint DEFAULT NULL COMMENT 'S5～S7',
  `resolved_by` varchar(32) DEFAULT NULL COMMENT '裁决人编号或 AUTO（S6、S7）',
  `dispute_deadline_at` datetime(3) DEFAULT NULL COMMENT 'S7',
  `void_reason` varchar(500) DEFAULT NULL COMMENT '作废原因：系统作废写原因代码（例如 NOT_ENOUGH_OPTIONS），人工作废写原因',
  `cancel_reason` varchar(500) DEFAULT NULL,
  `autogen_id` bigint DEFAULT NULL COMMENT '由哪个自动生成模板生成（S6）',
  `cover_path` varchar(300) DEFAULT NULL COMMENT '封面图（对象存储路径）',
  `created_by` bigint DEFAULT NULL COMMENT '管理员 id；系统生成的为空',
  `listed_by` bigint DEFAULT NULL COMMENT '上架的管理员 id；自动生成的为空',
  `deleted_at` datetime(3) DEFAULT NULL COMMENT '草稿删除时间（只有草稿能删；软删除，保留费率修改记录等关联）',
  `version` int NOT NULL DEFAULT '0' COMMENT '每次修改 +1',
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_market_public_no` (`public_no`),
  UNIQUE KEY `uk_market_autogen_window` (`autogen_id`,`event_start_at`),
  KEY `idx_market_status_close` (`status`,`close_at`),
  KEY `idx_market_status_end` (`status`,`event_end_at`),
  KEY `idx_market_status_deadline` (`status`,`dispute_deadline_at`),
  KEY `idx_market_list` (`status`,`category`,`close_at`),
  CONSTRAINT `ck_market_times` CHECK (((`open_at` < `close_at`) and (`close_at` <= `event_start_at`) and (`event_start_at` < `event_end_at`))),
  CONSTRAINT `ck_market_fee_rate` CHECK (((`fee_rate` >= 0) and (`fee_rate` <= 0.2))),
  CONSTRAINT `ck_market_user_cap` CHECK ((`user_cap` > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='市场（一个问题）';

CREATE TABLE `market_option` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_no` bigint NOT NULL COMMENT '对外编号（雪花 ID）',
  `market_id` bigint NOT NULL,
  `seq` int NOT NULL COMMENT '1～10，显示顺序',
  `label_en` varchar(200) NOT NULL DEFAULT '',
  `label_zh_cn` varchar(200) NOT NULL DEFAULT '',
  `label_zh_tw` varchar(200) NOT NULL DEFAULT '',
  `win_condition` json NOT NULL COMMENT '这个选项在什么情况下赢，三种语言 {"en","zhCN","zhTW"}',
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_market_option_public_no` (`public_no`),
  UNIQUE KEY `uk_market_option_seq` (`market_id`,`seq`),
  CONSTRAINT `ck_market_option_seq` CHECK (((`seq` >= 1) and (`seq` <= 10)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='市场的选项（2～10 个，上架后不能改：Mapper 的修改语句只对草稿生效）';

CREATE TABLE `market_status_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `market_id` bigint NOT NULL,
  `from_status` varchar(24) NOT NULL,
  `to_status` varchar(24) NOT NULL,
  `actor` varchar(32) NOT NULL COMMENT '管理员 id / SYSTEM / AUTO',
  `reason` varchar(500) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_market_status_log_market` (`market_id`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='市场状态历史（只增不改）：每次状态跳转一条，和跳转在同一个事务';

CREATE TABLE `market_clarification` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `market_id` bigint NOT NULL,
  `text_en` varchar(2000) NOT NULL,
  `text_zh_cn` varchar(2000) NOT NULL,
  `text_zh_tw` varchar(2000) NOT NULL,
  `created_by` bigint NOT NULL,
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_market_clarification_market` (`market_id`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='补充说明（只增不改，按时间顺序显示在规则区，需求第 3.2 节第 3 条）';

CREATE TABLE `market_fee_change` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `market_id` bigint NOT NULL,
  `old_rate` decimal(6,4) NOT NULL,
  `new_rate` decimal(6,4) NOT NULL,
  `reason` varchar(500) NOT NULL,
  `changed_by` bigint NOT NULL,
  `changed_at` datetime(3) NOT NULL,
  `notified` tinyint(1) NOT NULL COMMENT '是否已给有下注的客户发 #22（上架后改的发，草稿阶段不发）',
  PRIMARY KEY (`id`),
  KEY `idx_market_fee_change_market` (`market_id`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='市场费率修改记录（只增不改，公开显示时间和旧 → 新，需求第 3.8 节）';

CREATE TABLE `common_job_lease` (
  `name` varchar(64) NOT NULL COMMENT '任务名，例如 market.close',
  `owner` varchar(128) NOT NULL DEFAULT '' COMMENT '持有租约的实例',
  `lease_until` datetime(3) NOT NULL COMMENT '租约到期时间（数据库时间 UTC）；过期后别的实例可以接手',
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='定时任务租约（架构方案第 5.3 节）：多实例时同一个任务同一时间只有一个实例在跑';
