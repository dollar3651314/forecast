-- forecast S8：体育赛程 + 自动裁决（需求说明书 v1.3 第 3.6（E5～E7、E14）、4.2、4.3 节，MQ-10；技术方案/v1-架构方案.md v1.3 第 3.3、6 节，AQ-17、AQ-18；
-- 开发记录/S8-体育自动化.md）。V1～V15 没有改。resolve_* 只由 resolve 模块读写。时间一律 UTC、精确到毫秒。
-- 架构方案第 3.3 节把赛程表写成 market_fixture（market 模块）；赛程来自 resolve 的体育数据源，market 不能依赖 resolve（第 2.3 节依赖方向），
-- 所以赛程、两个来源的比赛对应、生成草稿都放在 resolve 模块，表名用 resolve_ 开头（开发记录第 6 节）。

-- 赛程（未来 7 天，按主源同步）和两个来源的比赛对应（X-12：运营确认）。
-- 主源：足球 football-data.org、NBA balldontlie（AQ-17）；备源 TheSportsDB 的比赛编号由运营从候选里确认后写进 backup_*（没有确认的，自动裁决只有一个来源，一律转人工，RQ-15）。
-- 同一个主源编号只有一行（唯一键），重复同步只更新。开赛时间变了记下来（E14：实际开赛时间以数据源为准），有关联市场时邮件提醒运营。
CREATE TABLE `resolve_fixture` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `public_no` bigint NOT NULL COMMENT '对外编号（雪花 ID，接口里是字符串）',
  `sport` varchar(12) NOT NULL COMMENT 'FOOTBALL / NBA',
  `competition_code` varchar(16) DEFAULT NULL COMMENT '赛事代码：football-data 的 PL、CL…；NBA',
  `competition_name` varchar(100) DEFAULT NULL,
  `league_ref` varchar(16) DEFAULT NULL COMMENT 'TheSportsDB 的联赛编号（按赛事代码对应，找候选用）',
  `home_name` varchar(100) NOT NULL,
  `home_short` varchar(60) DEFAULT NULL,
  `away_name` varchar(100) NOT NULL,
  `away_short` varchar(60) DEFAULT NULL,
  `kickoff_at` datetime(3) NOT NULL COMMENT '开赛时间（主源最新报告的）',
  `original_kickoff_at` datetime(3) NOT NULL COMMENT '第一次同步到的开赛时间',
  `primary_source` varchar(16) NOT NULL COMMENT 'FOOTBALL_DATA / BALLDONTLIE',
  `primary_ref` varchar(40) NOT NULL COMMENT '主源的比赛编号',
  `primary_status` varchar(16) NOT NULL COMMENT '统一后的状态：SCHEDULED / LIVE / FINISHED / POSTPONED / SUSPENDED / CANCELLED / AWARDED / UNKNOWN',
  `primary_raw_status` varchar(40) DEFAULT NULL COMMENT '主源的原始状态',
  `backup_source` varchar(16) DEFAULT NULL COMMENT '确认对应后：THESPORTSDB',
  `backup_ref` varchar(40) DEFAULT NULL COMMENT '确认对应的备源比赛编号；没有确认为空',
  `backup_swapped` tinyint(1) NOT NULL DEFAULT '0' COMMENT '备源的主客队和主源相反（中立场地等）：比较比分时对调',
  `backup_home` varchar(100) DEFAULT NULL,
  `backup_away` varchar(100) DEFAULT NULL,
  `backup_kickoff_at` datetime(3) DEFAULT NULL,
  `mapping_confirmed_by` bigint DEFAULT NULL COMMENT '确认对应的管理员（market:edit）',
  `mapping_confirmed_at` datetime(3) DEFAULT NULL,
  `candidates` json DEFAULT NULL COMMENT '系统按队名 + 开赛时间找的备源候选：[{ref, home, away, kickoffAt, league, score, swapped, suggested}]（只是建议，要人确认）',
  `candidates_at` datetime(3) DEFAULT NULL,
  `candidates_error` varchar(500) DEFAULT NULL COMMENT '最近一次找候选失败的原因（备源未开通、超时等）',
  `kickoff_alerted_at` datetime(3) DEFAULT NULL COMMENT '开赛时间变化的提醒发过（同一个新时间只发一次）',
  `fetched_at` datetime(3) NOT NULL COMMENT '最近一次同步',
  `version` int NOT NULL DEFAULT '0',
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_resolve_fixture_public_no` (`public_no`),
  UNIQUE KEY `uk_resolve_fixture_primary` (`primary_source`,`primary_ref`),
  KEY `idx_resolve_fixture_kickoff` (`sport`,`kickoff_at`),
  CONSTRAINT `ck_resolve_fixture_sport` CHECK ((`sport` in (_utf8mb4'FOOTBALL',_utf8mb4'NBA')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='体育赛程和两个数据源的比赛对应';

-- 从赛程生成的市场（每个市场一行）：哪场比赛、哪个玩法、分界线。同一场比赛同一玩法同一分界线只能有一个还有效的市场（应用层检查，草稿删除、取消、作废的不算）。
CREATE TABLE `resolve_fixture_market` (
  `market_id` bigint NOT NULL,
  `fixture_id` bigint NOT NULL,
  `play` varchar(16) NOT NULL COMMENT 'FOOTBALL_1X2 胜平负 / FOOTBALL_OU 大小球 / NBA_ML 胜负 / NBA_TOTAL 大小分（MQ-10）',
  `line` decimal(8,1) DEFAULT NULL COMMENT '大小球、大小分的分界线（必须是 .5，E8）',
  `created_by` bigint NOT NULL,
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`market_id`),
  KEY `idx_resolve_fixture_market` (`fixture_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='从赛程生成的市场';

-- 体育自动裁决的进度（每个市场一行，市场进入待裁决时建，INSERT IGNORE）。比赛、两个来源的编号在建的时候记下（之后改对应不影响已经开始裁决的市场）。
-- PENDING：等主源报告完赛 → 记下第一次看到完赛的时间，等 30 分钟（settle_after_at）→ 两个来源都完赛且比分一致 → RESOLVING（结果已定、正在结算）→ RESOLVED；
-- 比分不一致、状态异常（延期、腰斩、取消、加时点球、判定）、一直拿不到 → MANUAL（转人工，裁决台"自动裁决失败"；延期 / 腰斩同时标记 E6 / E7 等待）；
-- 市场已经不在待裁决（人工作废等）→ CLOSED。
CREATE TABLE `resolve_sports_task` (
  `market_id` bigint NOT NULL,
  `fixture_id` bigint NOT NULL,
  `sport` varchar(12) NOT NULL,
  `play` varchar(16) NOT NULL,
  `line` decimal(8,1) DEFAULT NULL,
  `primary_source` varchar(16) NOT NULL,
  `primary_ref` varchar(40) NOT NULL,
  `backup_source` varchar(16) DEFAULT NULL,
  `backup_ref` varchar(40) DEFAULT NULL,
  `backup_swapped` tinyint(1) NOT NULL DEFAULT '0',
  `close_at` datetime(3) NOT NULL COMMENT '市场的截止下注时间（E14：实际开赛早于它时，开赛后收到的下注退回）',
  `kickoff_at` datetime(3) NOT NULL COMMENT '市场的原定开赛时间（E6 的 48 小时从这里算）',
  `status` varchar(12) NOT NULL,
  `attempts` int NOT NULL DEFAULT '0',
  `next_attempt_at` datetime(3) NOT NULL,
  `finished_seen_at` datetime(3) DEFAULT NULL COMMENT '第一次看到主源报告完赛',
  `settle_after_at` datetime(3) DEFAULT NULL COMMENT '完赛后等 30 分钟（需求第 4.3 节）：到了才比较两个来源',
  `give_up_at` datetime(3) DEFAULT NULL COMMENT '过了这个时间备源还拿不到或还没完赛 → 转人工',
  `unfinished_deadline_at` datetime(3) NOT NULL COMMENT '过了这个时间主源还没报告完赛（也没有延期等状态）→ 转人工',
  `result_option_id` bigint DEFAULT NULL,
  `actual_start_at` datetime(3) DEFAULT NULL COMMENT '数据源报告的开赛时间（E14，交给结算）',
  `last_reason` varchar(500) DEFAULT NULL,
  `last_attempt_at` datetime(3) DEFAULT NULL,
  `version` int NOT NULL DEFAULT '0',
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`market_id`),
  KEY `idx_resolve_sports_task_due` (`status`,`next_attempt_at`),
  CONSTRAINT `ck_resolve_sports_task_status` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'RESOLVING',_utf8mb4'RESOLVED',_utf8mb4'MANUAL',_utf8mb4'CLOSED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='体育市场自动裁决的进度';

-- 体育的规则文本模板（每个玩法一个，三种语言，RQ-18）：从赛程生成草稿时自动填（需求第 4.2 节）。改模板只影响之后生成的草稿（草稿是快照）。
-- 变量（resolve/sports/SportsTemplates.java）：{home}、{away}、{competition}、{date}（开赛的 UTC 日期）、{kickoff}（HH:mm）、{closeTime}（截止，yyyy-MM-dd HH:mm）、
-- {line}（分界线）、{primarySource}、{backupSource}、{finishWaitMinutes}（完赛后等几分钟）。
CREATE TABLE `resolve_sports_template` (
  `kind` varchar(16) NOT NULL COMMENT 'FOOTBALL_1X2 / FOOTBALL_OU / NBA_ML / NBA_TOTAL',
  `name` varchar(100) NOT NULL,
  `title_en` varchar(300) NOT NULL,
  `title_zh_cn` varchar(300) NOT NULL,
  `title_zh_tw` varchar(300) NOT NULL,
  `rules_en` text NOT NULL,
  `rules_zh_cn` text NOT NULL,
  `rules_zh_tw` text NOT NULL,
  `source_primary` varchar(300) NOT NULL,
  `source_backup` varchar(300) NOT NULL,
  `options` json NOT NULL COMMENT '选项：[{"outcome":"HOME"|"DRAW"|"AWAY"|"OVER"|"UNDER","label":{en,zhCN,zhTW},"winCondition":{en,zhCN,zhTW}}]，按顺序',
  `version` int NOT NULL DEFAULT '0',
  `updated_by` bigint DEFAULT NULL,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`kind`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='体育规则文本模板（含变量）';

-- 四个玩法的规则文本（研发初稿，需求第 3.6、4.1、4.3 节的写法；PM 可以在后台改，S9 定稿）
INSERT INTO `resolve_sports_template` (`kind`, `name`, `title_en`, `title_zh_cn`, `title_zh_tw`, `rules_en`, `rules_zh_cn`, `rules_zh_tw`, `source_primary`, `source_backup`, `options`, `version`, `updated_by`, `created_at`, `updated_at`) VALUES (
  'FOOTBALL_1X2', '足球胜平负（90 分钟）',
  '{home} vs {away}: 90-minute result',
  '{home} vs {away}（90 分钟胜平负）',
  '{home} vs {away}（90 分鐘勝平負）',
  '{competition}, kick-off {date} {kickoff} UTC. Resolved by the score at the end of 90 minutes of regular time (including stoppage time); extra time and penalty shoot-outs do not count. Primary source: {primarySource}; backup source: {backupSource}. The market settles automatically {finishWaitMinutes} minutes after the match ends if both sources report the same final score; if they differ, only one source is available, or the match status is abnormal, it goes to manual resolution based on the primary source. Postponed: if the match is completed within 48 hours of the original kick-off, the actual result counts; otherwise the market is voided and all bets are refunded. Abandoned: if the league declares the score valid, the official score counts; if a replay is ordered or nothing is announced within 48 hours, the market is voided. Cancelled matches are voided. If the actual kick-off is earlier than the betting close, bets received after kick-off are refunded. Corrections after settlement do not change the result. Betting closes {closeTime} UTC.',
  '{competition}，{date} {kickoff} UTC 开赛。以 90 分钟常规时间（含伤停补时）结束时的比分为准，不含加时和点球。首选来源：{primarySource}；备用来源：{backupSource}。完赛 {finishWaitMinutes} 分钟后两个来源的比分一致即自动结算；不一致、只有一个来源或比赛状态异常时转人工裁决，人工以首选来源为准。延期：原定开赛时间后 48 小时内完赛的按实际结果，超过的作废、全额退款。腰斩：联赛官方宣布比分有效的按官方比分；宣布重赛或 48 小时内没有宣布的作废、全额退款。比赛取消的作废、全额退款。实际开赛时间早于截止时间时，开赛后收到的下注全额退回。结算后的数据修正不改变结果。截止下注：{closeTime} UTC。',
  '{competition}，{date} {kickoff} UTC 開賽。以 90 分鐘常規時間（含傷停補時）結束時的比分為準，不含加時和點球。首選來源：{primarySource}；備用來源：{backupSource}。完賽 {finishWaitMinutes} 分鐘後兩個來源的比分一致即自動結算；不一致、只有一個來源或比賽狀態異常時轉人工裁決，人工以首選來源為準。延期：原定開賽時間後 48 小時內完賽的按實際結果，超過的作廢、全額退款。腰斬：聯賽官方宣布比分有效的按官方比分；宣布重賽或 48 小時內沒有宣布的作廢、全額退款。比賽取消的作廢、全額退款。實際開賽時間早於截止時間時，開賽後收到的下注全額退回。結算後的資料修正不改變結果。截止下注：{closeTime} UTC。',
  'football-data.org', 'TheSportsDB',
  '[{"outcome":"HOME","label":{"en":"{home} win","zhCN":"主胜 {home}","zhTW":"主勝 {home}"},"winCondition":{"en":"{home} scores more goals in 90 minutes","zhCN":"90 分钟主队进球多于客队","zhTW":"90 分鐘主隊進球多於客隊"}},{"outcome":"DRAW","label":{"en":"Draw","zhCN":"平局","zhTW":"平局"},"winCondition":{"en":"Both teams score the same number of goals in 90 minutes","zhCN":"90 分钟两队进球相同","zhTW":"90 分鐘兩隊進球相同"}},{"outcome":"AWAY","label":{"en":"{away} win","zhCN":"客胜 {away}","zhTW":"客勝 {away}"},"winCondition":{"en":"{away} scores more goals in 90 minutes","zhCN":"90 分钟客队进球多于主队","zhTW":"90 分鐘客隊進球多於主隊"}}]',
  0, NULL, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3));

INSERT INTO `resolve_sports_template` (`kind`, `name`, `title_en`, `title_zh_cn`, `title_zh_tw`, `rules_en`, `rules_zh_cn`, `rules_zh_tw`, `source_primary`, `source_backup`, `options`, `version`, `updated_by`, `created_at`, `updated_at`) VALUES (
  'FOOTBALL_OU', '足球大小球（90 分钟）',
  '{home} vs {away}: total goals over/under {line}',
  '{home} vs {away}：总进球大于还是小于 {line}',
  '{home} vs {away}：總進球大於還是小於 {line}',
  '{competition}, kick-off {date} {kickoff} UTC. Resolved by the total goals of both teams at the end of 90 minutes of regular time (including stoppage time); extra time and penalty shoot-outs do not count. More than {line} goals is "Over", fewer is "Under" (the line ends in .5, so there is no tie). Primary source: {primarySource}; backup source: {backupSource}. The market settles automatically {finishWaitMinutes} minutes after the match ends if both sources report the same final score; if they differ, only one source is available, or the match status is abnormal, it goes to manual resolution based on the primary source. Postponed: if the match is completed within 48 hours of the original kick-off, the actual result counts; otherwise the market is voided and all bets are refunded. Abandoned: if the league declares the score valid, the official score counts; if a replay is ordered or nothing is announced within 48 hours, the market is voided. Cancelled matches are voided. If the actual kick-off is earlier than the betting close, bets received after kick-off are refunded. Corrections after settlement do not change the result. Betting closes {closeTime} UTC.',
  '{competition}，{date} {kickoff} UTC 开赛。以 90 分钟常规时间（含伤停补时）结束时两队进球总数为准，不含加时和点球；总进球大于 {line} 判"大"，小于 {line} 判"小"（分界线是 .5，不会正好相等）。首选来源：{primarySource}；备用来源：{backupSource}。完赛 {finishWaitMinutes} 分钟后两个来源的比分一致即自动结算；不一致、只有一个来源或比赛状态异常时转人工裁决，人工以首选来源为准。延期：原定开赛时间后 48 小时内完赛的按实际结果，超过的作废、全额退款。腰斩：联赛官方宣布比分有效的按官方比分；宣布重赛或 48 小时内没有宣布的作废、全额退款。比赛取消的作废、全额退款。实际开赛时间早于截止时间时，开赛后收到的下注全额退回。结算后的数据修正不改变结果。截止下注：{closeTime} UTC。',
  '{competition}，{date} {kickoff} UTC 開賽。以 90 分鐘常規時間（含傷停補時）結束時兩隊進球總數為準，不含加時和點球；總進球大於 {line} 判「大」，小於 {line} 判「小」（分界線是 .5，不會正好相等）。首選來源：{primarySource}；備用來源：{backupSource}。完賽 {finishWaitMinutes} 分鐘後兩個來源的比分一致即自動結算；不一致、只有一個來源或比賽狀態異常時轉人工裁決，人工以首選來源為準。延期：原定開賽時間後 48 小時內完賽的按實際結果，超過的作廢、全額退款。腰斬：聯賽官方宣布比分有效的按官方比分；宣布重賽或 48 小時內沒有宣布的作廢、全額退款。比賽取消的作廢、全額退款。實際開賽時間早於截止時間時，開賽後收到的下注全額退回。結算後的資料修正不改變結果。截止下注：{closeTime} UTC。',
  'football-data.org', 'TheSportsDB',
  '[{"outcome":"OVER","label":{"en":"Over {line}","zhCN":"大于 {line} 球","zhTW":"大於 {line} 球"},"winCondition":{"en":"Total goals in 90 minutes greater than {line}","zhCN":"90 分钟总进球大于 {line}","zhTW":"90 分鐘總進球大於 {line}"}},{"outcome":"UNDER","label":{"en":"Under {line}","zhCN":"小于 {line} 球","zhTW":"小於 {line} 球"},"winCondition":{"en":"Total goals in 90 minutes less than {line}","zhCN":"90 分钟总进球小于 {line}","zhTW":"90 分鐘總進球小於 {line}"}}]',
  0, NULL, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3));

INSERT INTO `resolve_sports_template` (`kind`, `name`, `title_en`, `title_zh_cn`, `title_zh_tw`, `rules_en`, `rules_zh_cn`, `rules_zh_tw`, `source_primary`, `source_backup`, `options`, `version`, `updated_by`, `created_at`, `updated_at`) VALUES (
  'NBA_ML', 'NBA 胜负（含加时）',
  '{home} vs {away}: winner (including overtime)',
  '{home} vs {away}：谁赢（含加时）',
  '{home} vs {away}：誰贏（含加時）',
  'NBA, tip-off {date} {kickoff} UTC. Resolved by the official final score including overtime (there are no ties in the NBA). Primary source: {primarySource}; backup source: {backupSource}. The market settles automatically {finishWaitMinutes} minutes after the game ends if both sources report the same final score; if they differ, only one source is available, or the game status is abnormal, it goes to manual resolution based on the primary source. Postponed: if the game is completed within 48 hours of the original tip-off, the actual result counts; otherwise the market is voided and all bets are refunded. Suspended: if the league declares the result valid, the official result counts; if nothing is announced within 48 hours, the market is voided. Cancelled games are voided. If the actual tip-off is earlier than the betting close, bets received after tip-off are refunded. Corrections after settlement do not change the result. Betting closes {closeTime} UTC.',
  'NBA，{date} {kickoff} UTC 开赛。以 NBA 官方最终比分为准，含加时（NBA 没有平局）。首选来源：{primarySource}；备用来源：{backupSource}。完赛 {finishWaitMinutes} 分钟后两个来源的比分一致即自动结算；不一致、只有一个来源或比赛状态异常时转人工裁决，人工以首选来源为准。延期：原定开赛时间后 48 小时内完赛的按实际结果，超过的作废、全额退款。中断：联盟宣布结果有效的按官方结果；48 小时内没有宣布的作废、全额退款。比赛取消的作废、全额退款。实际开赛时间早于截止时间时，开赛后收到的下注全额退回。结算后的数据修正不改变结果。截止下注：{closeTime} UTC。',
  'NBA，{date} {kickoff} UTC 開賽。以 NBA 官方最終比分為準，含加時（NBA 沒有平局）。首選來源：{primarySource}；備用來源：{backupSource}。完賽 {finishWaitMinutes} 分鐘後兩個來源的比分一致即自動結算；不一致、只有一個來源或比賽狀態異常時轉人工裁決，人工以首選來源為準。延期：原定開賽時間後 48 小時內完賽的按實際結果，超過的作廢、全額退款。中斷：聯盟宣布結果有效的按官方結果；48 小時內沒有宣布的作廢、全額退款。比賽取消的作廢、全額退款。實際開賽時間早於截止時間時，開賽後收到的下注全額退回。結算後的資料修正不改變結果。截止下注：{closeTime} UTC。',
  'balldontlie.io', 'TheSportsDB',
  '[{"outcome":"HOME","label":{"en":"{home} win","zhCN":"{home} 胜","zhTW":"{home} 勝"},"winCondition":{"en":"{home} has more points at the end of the game, including overtime","zhCN":"终场（含加时）主队得分多于客队","zhTW":"終場（含加時）主隊得分多於客隊"}},{"outcome":"AWAY","label":{"en":"{away} win","zhCN":"{away} 胜","zhTW":"{away} 勝"},"winCondition":{"en":"{away} has more points at the end of the game, including overtime","zhCN":"终场（含加时）客队得分多于主队","zhTW":"終場（含加時）客隊得分多於主隊"}}]',
  0, NULL, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3));

INSERT INTO `resolve_sports_template` (`kind`, `name`, `title_en`, `title_zh_cn`, `title_zh_tw`, `rules_en`, `rules_zh_cn`, `rules_zh_tw`, `source_primary`, `source_backup`, `options`, `version`, `updated_by`, `created_at`, `updated_at`) VALUES (
  'NBA_TOTAL', 'NBA 大小分（含加时）',
  '{home} vs {away}: total points over/under {line}',
  '{home} vs {away}：总得分大于还是小于 {line}',
  '{home} vs {away}：總得分大於還是小於 {line}',
  'NBA, tip-off {date} {kickoff} UTC. Resolved by the combined points of both teams in the official final score, including overtime. More than {line} points is "Over", fewer is "Under" (the line ends in .5, so there is no tie). Primary source: {primarySource}; backup source: {backupSource}. The market settles automatically {finishWaitMinutes} minutes after the game ends if both sources report the same final score; if they differ, only one source is available, or the game status is abnormal, it goes to manual resolution based on the primary source. Postponed: if the game is completed within 48 hours of the original tip-off, the actual result counts; otherwise the market is voided and all bets are refunded. Suspended: if the league declares the result valid, the official result counts; if nothing is announced within 48 hours, the market is voided. Cancelled games are voided. If the actual tip-off is earlier than the betting close, bets received after tip-off are refunded. Corrections after settlement do not change the result. Betting closes {closeTime} UTC.',
  'NBA，{date} {kickoff} UTC 开赛。以 NBA 官方最终比分里两队得分之和为准，含加时；总得分大于 {line} 判"大"，小于 {line} 判"小"（分界线是 .5，不会正好相等）。首选来源：{primarySource}；备用来源：{backupSource}。完赛 {finishWaitMinutes} 分钟后两个来源的比分一致即自动结算；不一致、只有一个来源或比赛状态异常时转人工裁决，人工以首选来源为准。延期：原定开赛时间后 48 小时内完赛的按实际结果，超过的作废、全额退款。中断：联盟宣布结果有效的按官方结果；48 小时内没有宣布的作废、全额退款。比赛取消的作废、全额退款。实际开赛时间早于截止时间时，开赛后收到的下注全额退回。结算后的数据修正不改变结果。截止下注：{closeTime} UTC。',
  'NBA，{date} {kickoff} UTC 開賽。以 NBA 官方最終比分裡兩隊得分之和為準，含加時；總得分大於 {line} 判「大」，小於 {line} 判「小」（分界線是 .5，不會正好相等）。首選來源：{primarySource}；備用來源：{backupSource}。完賽 {finishWaitMinutes} 分鐘後兩個來源的比分一致即自動結算；不一致、只有一個來源或比賽狀態異常時轉人工裁決，人工以首選來源為準。延期：原定開賽時間後 48 小時內完賽的按實際結果，超過的作廢、全額退款。中斷：聯盟宣布結果有效的按官方結果；48 小時內沒有宣布的作廢、全額退款。比賽取消的作廢、全額退款。實際開賽時間早於截止時間時，開賽後收到的下注全額退回。結算後的資料修正不改變結果。截止下注：{closeTime} UTC。',
  'balldontlie.io', 'TheSportsDB',
  '[{"outcome":"OVER","label":{"en":"Over {line}","zhCN":"大于 {line} 分","zhTW":"大於 {line} 分"},"winCondition":{"en":"Total points including overtime greater than {line}","zhCN":"总得分（含加时）大于 {line}","zhTW":"總得分（含加時）大於 {line}"}},{"outcome":"UNDER","label":{"en":"Under {line}","zhCN":"小于 {line} 分","zhTW":"小於 {line} 分"},"winCondition":{"en":"Total points including overtime less than {line}","zhCN":"总得分（含加时）小于 {line}","zhTW":"總得分（含加時）小於 {line}"}}]',
  0, NULL, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3));

-- 参数（参数设置页，betting_params:edit；需求第 3.7 节第 5 条"各品类的默认值，新建时自动带出，后台参数可改"）：
-- 足球、NBA 开赛前 5 分钟截止；事件结束 = 开赛 + 2 小时（足球）/ 3 小时（NBA），到了进入待裁决、开始看数据源是否完赛
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('sports.closeLeadMinutes.FOOTBALL','5',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('sports.closeLeadMinutes.NBA','5',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('sports.eventMinutes.FOOTBALL','120',NULL);
INSERT INTO `common_config` (`config_key`, `value`, `updated_by`) VALUES ('sports.eventMinutes.NBA','180',NULL);

-- 员工告警（邮件，收件人 FC_RISK_ALERT_EMAILS，同 S6、S7 的口径 SQ-14）。文案是研发初稿，PM 在 S9 定稿。
INSERT INTO `notify_template` (`code`, `audience`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `variables`, `channels`, `built_in`, `enabled`) VALUES ('SPORTS_AUTO_FAILED','STAFF',NULL,'体育自动裁决转人工','Sports auto resolution needs a person','市场 {marketNo} 体育自动裁决转人工','Market {marketNo}: sports auto resolution needs manual handling','市场 {marketNo}（{market}）没有自动结算，已转人工裁决。\n原因：{reason}\n{hold}\n请在后台"裁决台 → 自动裁决失败"查看两个数据源的原始返回（市场详情 → 裁决依据），按首选来源判定、等待或作废。','Market {marketNo} ({marketEn}) was not settled automatically and now needs manual resolution.\nReason: {reason}\n{holdEn}\nCheck the raw responses of both data sources in the admin console (Resolution → Auto resolution failed) and resolve it by the primary source, wait, or void it.','marketNo,market,marketEn,reason,hold,holdEn','EMAIL',1,1);
INSERT INTO `notify_template` (`code`, `audience`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `variables`, `channels`, `built_in`, `enabled`) VALUES ('SPORTS_KICKOFF_CHANGED','STAFF',NULL,'比赛开赛时间变了','Kick-off time changed','{fixture} 开赛时间变了','{fixture}: kick-off time changed','比赛 {fixture} 的开赛时间从 {oldKickoff} 改成了 {newKickoff}（数据源 {source}）。\n关联的市场：{markets}\n还在下注中的市场如果截止时间晚于新的开赛时间，请提前截止（实际开赛后收到的下注结算时会全额退回，E14）。','The kick-off of {fixture} changed from {oldKickoff} to {newKickoff} (source: {source}).\nRelated markets: {markets}\nIf a market is still open and closes after the new kick-off, advance its close time (bets received after the actual kick-off are refunded at settlement, E14).','fixture,oldKickoff,newKickoff,source,markets','EMAIL',1,1);
