-- forecast S6：加密市场自动生成 + 自动裁决（技术方案/v1-架构方案.md v1.1.1 第 3.3 节 market_template、market_autogen、resolve 模块，第 6 节；开发记录/S6-加密自动化.md）。
-- 架构方案第 3.4 节原来把 resolve 排在 V9，S2～S5 用到了 V13，S6 从 V14 编号。
-- market_template、market_autogen 只由 market 模块读写；resolve_* 只由 resolve 模块读写。
-- resolve_source_fetch（数据源原始返回存档）、resolve_record（裁决记录）只增不改（Mapper 里没有它们的 UPDATE / DELETE，ResolveAppendOnlyTest 扫描），永久保留（需求 NF-09）。
-- 时间一律 UTC、精确到毫秒。

-- 规则文本模板（需求第 4.2 节"规则文本模板（含变量：币种、窗口开始和结束）"）。第一期只有加密涨跌一个模板；变量见 market/autogen/AutogenRender.java：
-- {asset}（BTC）、{pair}（BTC/USDT）、{okxPair}（BTC-USDT）、{date}（2026-11-01）、{startTime}（13:00）、{endTime}（14:00）、{minutes}（60）、{closeLeadSeconds}（30）
CREATE TABLE `market_template` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_no` bigint NOT NULL COMMENT '对外编号（雪花 ID，接口里是字符串）',
  `kind` varchar(24) NOT NULL COMMENT 'CRYPTO_UPDOWN（加密涨跌）；以后的体育模板另加',
  `name` varchar(100) NOT NULL,
  `title_en` varchar(300) NOT NULL,
  `title_zh_cn` varchar(300) NOT NULL,
  `title_zh_tw` varchar(300) NOT NULL,
  `rules_en` text NOT NULL,
  `rules_zh_cn` text NOT NULL,
  `rules_zh_tw` text NOT NULL,
  `source_primary` varchar(300) NOT NULL,
  `source_backup` varchar(300) NOT NULL,
  `options` json NOT NULL COMMENT '选项：[{"outcome":"UP"|"DOWN","label":{en,zhCN,zhTW},"winCondition":{en,zhCN,zhTW}}]，按顺序',
  `version` int NOT NULL DEFAULT '0' COMMENT '每次修改 +1；自动生成模板启用时记下检查的是哪个版本',
  `updated_by` bigint DEFAULT NULL,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_market_template_public_no` (`public_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='市场规则文本模板（含变量）';

-- 加密自动生成模板（需求第 4.2 节；MQ-1 节目化：按"场次"配置）。
-- mode = SCHEDULE（节目化，默认）：slots 里的每个 UTC 窗口开始时间每天一场，在开放下注前 generate_ahead_min 分钟生成；
-- mode = ROLLING（原来的做法，可切换）：始终保证未来 rolling_count 个窗口已上架（可下注或待开放）。
-- 开放、截止的提前量用参数设置里的 crypto.openLeadMinutes.*、crypto.closeLeadSeconds（betting_params:edit），费率用品类默认值（fee:edit），模板里不另设。
-- 改配置只能在关闭时（market:autogen）；启用要 market:publish 按第 4.1 节清单检查一次（checked_by / checked_at）。
CREATE TABLE `market_autogen` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_no` bigint NOT NULL COMMENT '对外编号（雪花 ID，接口里是字符串）',
  `symbol` varchar(16) NOT NULL COMMENT 'BTCUSDT / ETHUSDT（RQ-13）',
  `period` varchar(4) NOT NULL COMMENT '15M / 1H',
  `mode` varchar(12) NOT NULL COMMENT 'SCHEDULE 按场次（节目化）/ ROLLING 保证未来至少 N 个窗口',
  `slots` json NOT NULL COMMENT 'SCHEDULE：每天的窗口开始时间（UTC，"HH:mm"，15M 是 15 分钟的整数倍、1H 是整点），例如 ["13:00"]',
  `rolling_count` int NOT NULL COMMENT 'ROLLING：未来至少几个窗口（15 分钟默认 4、1 小时默认 3）',
  `generate_ahead_min` int NOT NULL COMMENT 'SCHEDULE：开放下注前多少分钟生成（生成即上架，开放前显示"待开放"）',
  `template_id` bigint NOT NULL COMMENT '规则文本模板 market_template.id',
  `user_cap` decimal(24,8) DEFAULT NULL COMMENT '单客户单市场上限；为空用品类默认值，只能调低',
  `enabled` tinyint(1) NOT NULL DEFAULT '0',
  `checked_by` bigint DEFAULT NULL COMMENT '启用时检查的人（market:publish）',
  `checked_at` datetime(3) DEFAULT NULL,
  `checked_template_version` int DEFAULT NULL COMMENT '检查时规则文本模板的版本',
  `version` int NOT NULL DEFAULT '0',
  `created_by` bigint DEFAULT NULL,
  `updated_by` bigint DEFAULT NULL,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_market_autogen_public_no` (`public_no`),
  UNIQUE KEY `uk_market_autogen_symbol_period` (`symbol`,`period`),
  CONSTRAINT `ck_market_autogen_period` CHECK ((`period` in (_utf8mb4'15M',_utf8mb4'1H'))),
  CONSTRAINT `ck_market_autogen_mode` CHECK ((`mode` in (_utf8mb4'SCHEDULE',_utf8mb4'ROLLING'))),
  CONSTRAINT `ck_market_autogen_counts` CHECK (((`rolling_count` between 1 and 96) and (`generate_ahead_min` between 0 and 10080)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='加密市场自动生成模板（按模板生成的市场直接上架）';

-- 数据源原始返回存档（第 6.3 节）：每次 HTTP 调用（成功、失败、超时、熔断跳过）一行。只增不改，永久保留。
CREATE TABLE `resolve_source_fetch` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `market_id` bigint DEFAULT NULL COMMENT '为哪个市场取的；冒烟测试等没有市场的为空',
  `source` varchar(16) NOT NULL COMMENT 'BINANCE / OKX / COINBASE',
  `attempt` int NOT NULL COMMENT '这一轮取数的第几次尝试（有上限的退避重试）',
  `request_url` varchar(1000) NOT NULL COMMENT '请求地址（公开接口，不带密钥）',
  `http_status` int DEFAULT NULL COMMENT '没有拿到响应（超时、连不上、熔断）为空',
  `body` mediumtext COMMENT '原文（最多 1 MB，超过的不保存原文）',
  `body_sha256` char(64) DEFAULT NULL COMMENT '原文的 sha256（十六进制）',
  `outcome` varchar(16) NOT NULL COMMENT 'OK / NOT_READY（K 线还不全或未收盘）/ FAILED / CIRCUIT_OPEN',
  `parsed` json DEFAULT NULL COMMENT '解析结果：开始价、结束价、方向、K 线根数',
  `error` varchar(500) DEFAULT NULL,
  `fetched_at` datetime(3) NOT NULL,
  `elapsed_ms` int NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_resolve_fetch_market` (`market_id`,`source`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='数据源原始返回存档（只增不改，永久保留）';

-- 裁决记录（第 3.3 节，只增不改）：S6 只写 AUTO_RESULT、AUTO_FAILED；提议、确认、争议在 S7。
CREATE TABLE `resolve_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `market_id` bigint NOT NULL,
  `kind` varchar(24) NOT NULL COMMENT 'AUTO_RESULT 自动裁决的结果 / AUTO_FAILED 自动裁决失败转人工（S7 起还有 PROPOSE、CONFIRM 等）',
  `option_id` bigint DEFAULT NULL,
  `detail` json DEFAULT NULL COMMENT '依据：各来源的开始价、结束价、方向、存档行 id',
  `note` varchar(500) DEFAULT NULL,
  `actor` varchar(32) NOT NULL COMMENT 'AUTO 或管理员 id',
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_resolve_record_market` (`market_id`,`id`),
  KEY `idx_resolve_record_kind` (`kind`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='裁决记录（只增不改）';

-- 自动裁决的进度（每个加密市场一行）：市场进入待裁决时建（market 的事务里，INSERT IGNORE），裁决任务按 next_attempt_at 扫描。
-- PENDING 等待取数（每分钟重试）→ RESOLVING 已定结果、正在结算（崩溃后续跑）→ RESOLVED；或 MANUAL（30 分钟仍不一致或拿不到，转人工，S7 裁决台处理）；
-- CLOSED：市场已经不在待裁决（人工作废等），不再处理。
CREATE TABLE `resolve_auto_task` (
  `market_id` bigint NOT NULL,
  `symbol` varchar(16) NOT NULL,
  `window_start` datetime(3) NOT NULL,
  `window_end` datetime(3) NOT NULL,
  `status` varchar(12) NOT NULL,
  `attempts` int NOT NULL DEFAULT '0',
  `next_attempt_at` datetime(3) NOT NULL,
  `deadline_at` datetime(3) NOT NULL COMMENT '过了这个时间还不一致或拿不到 → 转人工（窗口结束 + 1 分钟 + 30 分钟）',
  `result_option_id` bigint DEFAULT NULL,
  `last_reason` varchar(500) DEFAULT NULL,
  `last_attempt_at` datetime(3) DEFAULT NULL,
  `version` int NOT NULL DEFAULT '0',
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`market_id`),
  KEY `idx_resolve_task_due` (`status`,`next_attempt_at`),
  CONSTRAINT `ck_resolve_task_status` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'RESOLVING',_utf8mb4'RESOLVED',_utf8mb4'MANUAL',_utf8mb4'CLOSED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='加密市场自动裁决的进度';

-- 加密涨跌的规则文本模板（研发初稿，需求第 4.3 节的写法；PM 可以在后台改，改后要重新检查启用）
INSERT INTO `market_template` (`public_no`, `kind`, `name`, `title_en`, `title_zh_cn`, `title_zh_tw`, `rules_en`, `rules_zh_cn`, `rules_zh_tw`, `source_primary`, `source_backup`, `options`, `version`, `updated_by`, `created_at`, `updated_at`) VALUES (
  1000000000000000001, 'CRYPTO_UPDOWN', '加密涨跌（币安 K 线）',
  '{asset} Up or Down: {date} {startTime}–{endTime} UTC',
  '{asset} 涨还是跌：{date} {startTime}–{endTime} UTC',
  '{asset} 漲還是跌：{date} {startTime}–{endTime} UTC',
  'Resolved with Binance spot {pair} 1-minute klines. The open of the first 1-minute kline in the window ({date} {startTime}–{endTime} UTC) is the start price; the close of the last 1-minute kline is the end price. If the end price is greater than or equal to the start price, the market resolves to "Up"; otherwise "Down" (an exact tie is "Up"). Backup source: OKX spot {okxPair} 1-minute candles over the same window. The market settles automatically when both sources show the same direction; if they disagree on a tie, Binance prevails. If the sources disagree or data is unavailable for more than 30 minutes, the market goes to manual resolution based on the Binance data; if it still cannot be determined, the market is voided and all bets are refunded. Betting closes {closeLeadSeconds} seconds before the window starts.',
  '以币安现货 {pair} 的 1 分钟 K 线为准：窗口（{date} {startTime}–{endTime} UTC）内第一根 1 分钟 K 线的开盘价为开始价，最后一根 1 分钟 K 线的收盘价为结束价；结束价 ≥ 开始价判"涨"，否则判"跌"（正好相等判"涨"）。备用来源：OKX 现货 {okxPair} 同一窗口的 1 分钟 K 线。两个来源的涨跌方向一致时自动结算；正好相等导致方向不同时以币安为准。两个来源不一致或拿不到数据超过 30 分钟，转人工按币安的数据判定；仍无法确定的作废，全额退款。截止下注：窗口开始前 {closeLeadSeconds} 秒。',
  '以幣安現貨 {pair} 的 1 分鐘 K 線為準：窗口（{date} {startTime}–{endTime} UTC）內第一根 1 分鐘 K 線的開盤價為開始價，最後一根 1 分鐘 K 線的收盤價為結束價；結束價 ≥ 開始價判「漲」，否則判「跌」（正好相等判「漲」）。備用來源：OKX 現貨 {okxPair} 同一窗口的 1 分鐘 K 線。兩個來源的漲跌方向一致時自動結算；正好相等導致方向不同時以幣安為準。兩個來源不一致或拿不到資料超過 30 分鐘，轉人工按幣安的資料判定；仍無法確定的作廢，全額退款。截止下注：窗口開始前 {closeLeadSeconds} 秒。',
  'Binance spot {pair} 1m klines (data-api.binance.vision)',
  'OKX spot {okxPair} 1m candles (okx.com)',
  '[{"outcome":"UP","label":{"en":"Up","zhCN":"涨","zhTW":"漲"},"winCondition":{"en":"End price ≥ start price","zhCN":"结束价 ≥ 开始价","zhTW":"結束價 ≥ 開始價"}},{"outcome":"DOWN","label":{"en":"Down","zhCN":"跌","zhTW":"跌"},"winCondition":{"en":"End price < start price","zhCN":"结束价 < 开始价","zhTW":"結束價 < 開始價"}}]',
  0, NULL, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3));

-- 默认按节目化配置（MQ-1，Amos 已同意）：冷启动期只开 BTC 1 小时，每天 13:00–14:00 UTC（北京时间 21:00–22:00）一场。
-- 新库里是关闭的：启用前要有 market:publish 的人在后台按第 4.1 节清单检查一次（需求第 4.2 节）。
INSERT INTO `market_autogen` (`public_no`, `symbol`, `period`, `mode`, `slots`, `rolling_count`, `generate_ahead_min`, `template_id`, `user_cap`, `enabled`, `version`, `created_at`, `updated_at`)
SELECT 1000000000000000002, 'BTCUSDT', '1H', 'SCHEDULE', '["13:00"]', 3, 60, t.id, NULL, 0, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
FROM `market_template` t WHERE t.public_no = 1000000000000000001;

-- 员工告警：自动裁决失败转人工（需求第 7 节"自动裁决失败转人工 → 裁决员 + 超管"，AC-29）。只发邮件（告警邮箱 FC_RISK_ALERT_EMAILS）。文案是研发初稿，PM 在 S9 定稿。
INSERT INTO `notify_template` (`code`, `audience`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `variables`, `channels`, `built_in`, `enabled`) VALUES ('RESOLVE_AUTO_FAILED','STAFF',NULL,'自动裁决失败转人工','Auto resolution failed','市场 {marketNo} 自动裁决失败，已转人工','Market {marketNo}: auto resolution failed, manual resolution needed','市场 {marketNo}（{market}）窗口结束后 {minutes} 分钟内两个数据源仍然不一致或拿不到数据，已转人工裁决。\n最后一次：{reason}\n请在后台"裁决台 → 自动裁决失败"查看两个来源的原始数据（市场详情 → 裁决依据），按首选来源判定或作废。','For market {marketNo} ({marketEn}), the two data sources still disagreed or were unavailable {minutes} minutes after the window ended. It now needs manual resolution.\nLast attempt: {reason}\nCheck the raw data of both sources in the admin console (Resolution → Auto resolution failed) and resolve it by the primary source or void it.','marketNo,market,marketEn,minutes,reason','EMAIL',1,1);
