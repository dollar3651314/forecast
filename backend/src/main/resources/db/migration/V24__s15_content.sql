-- forecast S15：事件和文章自动化程序第一期（需求文档/v1.3-事件和文章自动化.md v1.1，EQ-1～EQ-8 按建议；需求说明书 v1.3.2 第 6.3、6.4 节、MF-9、MF-11、
-- NF-15、NF-16；架构方案第 18.3 节；开发记录/S15-自动化程序.md）。V1～V23 已合并，不再修改。表名以 content_ 开头，只由 content 模块读写。
--
-- 1. content_setting：内容中心的设置（每日 Gemini 调用上限 300、文章起草能用的比例 80%（体育抓取优先，EQ-5）、费用提醒线、文章起草和每日草稿包的开关、
--    每天新增页面上限 5、每周文章上限 2（EQ-7）、经济日历提前几天生成草稿、紧急停止）。由 content:publish 修改（高危，写审计），不在系统配置页和参数设置页。
-- 2. content_word：禁用词（F-12、F-15、市场策略第 9 节）、倾向词（EQ-8：不写倾向）、敏感词（需求第 4.1 节"不能涉及"），后台可以加；内置的不能删。
-- 3. content_template：经济日历的题型模板（FOMC、CPI，自动化需求第 3.2 节；三种语言；保存时检查禁用词）。
-- 4. content_econ_event：经济日历（运营录入 FOMC 声明、CPI 公布的日期时间，EQ-6）；事件前 7 天自动按模板生成市场草稿（只是草稿，要人检查上架）。
-- 5. content_filter_log：被敏感题过滤掉的事件（原因、命中的词），"已过滤"日志。
-- 6. content_draft：草稿箱（Gemini 起草的赛前 / 行情短文、SEO 页面说明、文章、X / Telegram 帖子；中文、英文各一行，group_no 相同）。
--    每条带"引用的数据"（refs）和检查结果（数字核对、禁用词、倾向词）；全部要 Amos 审核（EQ-1：第一期没有自动发布）。
-- 7. content_page：网站上的 SEO 页面（焦点战页、BTC 每小时涨跌统计页、文章页，MF-11）；数据部分自动，说明文字和文章审核后才有。
-- 8. content_log：生成、发布、驳回、复制、编辑、过滤、设置、拦截（NF-16）的日志。
-- 9. content_ai_day：每天的 Gemini 调用次数（条件更新计数，不超过上限）、失败次数、连续失败、当天已发的告警。
-- 10. 员工邮件模板 CONTENT_ALERT（Gemini 未配置 / 不可用 / 额度用完 / 连续失败，每天最多一封）、CONTENT_DIGEST（每日草稿包）；收件人 content:publish。
-- 11. 菜单"内容中心"（需求第 6.1 节）：草稿箱、事件候选、网站页面、模板、工具、日志、用量和设置（含渠道开关的占位，等 MF-7）。

CREATE TABLE `content_setting` (
  `setting_key` varchar(64) NOT NULL,
  `value` varchar(500) NOT NULL,
  `updated_by` bigint DEFAULT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`setting_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='内容中心的设置（S15；content:publish 修改，写审计）';

INSERT INTO `content_setting` (`setting_key`, `value`, `updated_at`) VALUES
  ('ai.dailyLimit', '300', UTC_TIMESTAMP(3)),
  ('ai.contentSharePct', '80', UTC_TIMESTAMP(3)),
  ('ai.costAlertUsd', '10', UTC_TIMESTAMP(3)),
  ('ai.drafts', '1', UTC_TIMESTAMP(3)),
  ('pack.enabled', '1', UTC_TIMESTAMP(3)),
  ('pages.dailyNew', '5', UTC_TIMESTAMP(3)),
  ('articles.weekly', '2', UTC_TIMESTAMP(3)),
  ('econ.leadDays', '7', UTC_TIMESTAMP(3)),
  ('publish.stopped', '0', UTC_TIMESTAMP(3));

CREATE TABLE `content_word` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `kind` varchar(12) NOT NULL COMMENT 'BANNED 禁用的说法 / TENDENCY 倾向（EQ-8）/ SENSITIVE 敏感题（需求第 4.1 节）',
  `word` varchar(64) NOT NULL COMMENT '中文按包含匹配；英文按整词匹配，不分大小写',
  `built_in` tinyint(1) NOT NULL DEFAULT '0',
  `created_by` bigint DEFAULT NULL,
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_content_word` (`kind`, `word`),
  CONSTRAINT `ck_content_word_kind` CHECK ((`kind` in (_utf8mb4'BANNED', _utf8mb4'TENDENCY', _utf8mb4'SENSITIVE')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='禁用词、倾向词、敏感词（S15；后台可加，内置的不能删）';

INSERT INTO `content_word` (`kind`, `word`, `built_in`, `created_at`) VALUES
  ('BANNED', '稳赚', 1, UTC_TIMESTAMP(3)), ('BANNED', '必中', 1, UTC_TIMESTAMP(3)), ('BANNED', '保本', 1, UTC_TIMESTAMP(3)),
  ('BANNED', '翻倍', 1, UTC_TIMESTAMP(3)), ('BANNED', '胜者瓜分', 1, UTC_TIMESTAMP(3)), ('BANNED', '瓜分对手', 1, UTC_TIMESTAMP(3)),
  ('BANNED', '最后入场', 1, UTC_TIMESTAMP(3)), ('BANNED', '别错过', 1, UTC_TIMESTAMP(3)), ('BANNED', '秒出', 1, UTC_TIMESTAMP(3)),
  ('BANNED', '全在链上', 1, UTC_TIMESTAMP(3)), ('BANNED', '链上公开可查', 1, UTC_TIMESTAMP(3)), ('BANNED', '私房钱', 1, UTC_TIMESTAMP(3)),
  ('BANNED', '恭喜', 1, UTC_TIMESTAMP(3)), ('BANNED', '穩賺', 1, UTC_TIMESTAMP(3)), ('BANNED', '保證', 1, UTC_TIMESTAMP(3)),
  ('BANNED', '保证', 1, UTC_TIMESTAMP(3)), ('BANNED', '勝者瓜分', 1, UTC_TIMESTAMP(3)), ('BANNED', '最後入場', 1, UTC_TIMESTAMP(3)),
  ('BANNED', '別錯過', 1, UTC_TIMESTAMP(3)), ('BANNED', '客服装死', 1, UTC_TIMESTAMP(3)), ('BANNED', '提现慢', 1, UTC_TIMESTAMP(3)),
  ('BANNED', 'Polymarket 中文', 1, UTC_TIMESTAMP(3)), ('BANNED', 'Polymarket 官方华语', 1, UTC_TIMESTAMP(3)), ('BANNED', '资深操盘手', 1, UTC_TIMESTAMP(3)),
  ('BANNED', 'guaranteed', 1, UTC_TIMESTAMP(3)), ('BANNED', 'risk-free', 1, UTC_TIMESTAMP(3)), ('BANNED', 'don''t miss', 1, UTC_TIMESTAMP(3)),
  ('BANNED', 'last chance', 1, UTC_TIMESTAMP(3)), ('BANNED', 'double your', 1, UTC_TIMESTAMP(3)), ('BANNED', 'sure win', 1, UTC_TIMESTAMP(3)),
  ('BANNED', 'congratulations', 1, UTC_TIMESTAMP(3)), ('BANNED', 'winner takes all', 1, UTC_TIMESTAMP(3)),
  ('TENDENCY', '必涨', 1, UTC_TIMESTAMP(3)), ('TENDENCY', '必跌', 1, UTC_TIMESTAMP(3)), ('TENDENCY', '看好', 1, UTC_TIMESTAMP(3)),
  ('TENDENCY', '建议押', 1, UTC_TIMESTAMP(3)), ('TENDENCY', '推荐押', 1, UTC_TIMESTAMP(3)), ('TENDENCY', '值得押', 1, UTC_TIMESTAMP(3)),
  ('TENDENCY', '偏向', 1, UTC_TIMESTAMP(3)), ('TENDENCY', '倾向于', 1, UTC_TIMESTAMP(3)), ('TENDENCY', '稳了', 1, UTC_TIMESTAMP(3)),
  ('TENDENCY', '必出大球', 1, UTC_TIMESTAMP(3)), ('TENDENCY', '更可能赢', 1, UTC_TIMESTAMP(3)), ('TENDENCY', '大概率', 1, UTC_TIMESTAMP(3)),
  ('TENDENCY', 'should bet', 1, UTC_TIMESTAMP(3)), ('TENDENCY', 'we recommend', 1, UTC_TIMESTAMP(3)), ('TENDENCY', 'likely to win', 1, UTC_TIMESTAMP(3)),
  ('TENDENCY', 'favored to win', 1, UTC_TIMESTAMP(3)), ('TENDENCY', 'lean towards', 1, UTC_TIMESTAMP(3)), ('TENDENCY', 'sure thing', 1, UTC_TIMESTAMP(3)),
  ('TENDENCY', 'will definitely', 1, UTC_TIMESTAMP(3)),
  ('SENSITIVE', '选举', 1, UTC_TIMESTAMP(3)), ('SENSITIVE', '大选', 1, UTC_TIMESTAMP(3)), ('SENSITIVE', '总统', 1, UTC_TIMESTAMP(3)),
  ('SENSITIVE', '候选人', 1, UTC_TIMESTAMP(3)), ('SENSITIVE', '议员', 1, UTC_TIMESTAMP(3)), ('SENSITIVE', '政党', 1, UTC_TIMESTAMP(3)),
  ('SENSITIVE', '战争', 1, UTC_TIMESTAMP(3)), ('SENSITIVE', '伤亡', 1, UTC_TIMESTAMP(3)), ('SENSITIVE', '死亡人数', 1, UTC_TIMESTAMP(3)),
  ('SENSITIVE', '暗杀', 1, UTC_TIMESTAMP(3)), ('SENSITIVE', '地震', 1, UTC_TIMESTAMP(3)), ('SENSITIVE', '灾害', 1, UTC_TIMESTAMP(3)),
  ('SENSITIVE', '恐袭', 1, UTC_TIMESTAMP(3)), ('SENSITIVE', '儿童', 1, UTC_TIMESTAMP(3)), ('SENSITIVE', '未成年', 1, UTC_TIMESTAMP(3)),
  ('SENSITIVE', '青少年', 1, UTC_TIMESTAMP(3)), ('SENSITIVE', '青年队', 1, UTC_TIMESTAMP(3)), ('SENSITIVE', '主播', 1, UTC_TIMESTAMP(3)),
  ('SENSITIVE', '会不会说', 1, UTC_TIMESTAMP(3)), ('SENSITIVE', '隐私', 1, UTC_TIMESTAMP(3)), ('SENSITIVE', '選舉', 1, UTC_TIMESTAMP(3)),
  ('SENSITIVE', '戰爭', 1, UTC_TIMESTAMP(3)), ('SENSITIVE', 'U17', 1, UTC_TIMESTAMP(3)), ('SENSITIVE', 'U18', 1, UTC_TIMESTAMP(3)),
  ('SENSITIVE', 'U19', 1, UTC_TIMESTAMP(3)), ('SENSITIVE', 'U20', 1, UTC_TIMESTAMP(3)), ('SENSITIVE', 'U21', 1, UTC_TIMESTAMP(3)),
  ('SENSITIVE', 'youth', 1, UTC_TIMESTAMP(3)), ('SENSITIVE', 'junior', 1, UTC_TIMESTAMP(3)), ('SENSITIVE', 'election', 1, UTC_TIMESTAMP(3)),
  ('SENSITIVE', 'president', 1, UTC_TIMESTAMP(3)), ('SENSITIVE', 'candidate', 1, UTC_TIMESTAMP(3)), ('SENSITIVE', 'war', 1, UTC_TIMESTAMP(3)),
  ('SENSITIVE', 'casualties', 1, UTC_TIMESTAMP(3)), ('SENSITIVE', 'death toll', 1, UTC_TIMESTAMP(3)), ('SENSITIVE', 'assassination', 1, UTC_TIMESTAMP(3)),
  ('SENSITIVE', 'earthquake', 1, UTC_TIMESTAMP(3)), ('SENSITIVE', 'terror', 1, UTC_TIMESTAMP(3)), ('SENSITIVE', 'children', 1, UTC_TIMESTAMP(3)),
  ('SENSITIVE', 'streamer', 1, UTC_TIMESTAMP(3));

CREATE TABLE `content_template` (
  `code` varchar(16) NOT NULL COMMENT 'FOMC / CPI',
  `title_en` varchar(300) NOT NULL,
  `title_zh_cn` varchar(300) NOT NULL,
  `title_zh_tw` varchar(300) NOT NULL,
  `rules_en` text NOT NULL,
  `rules_zh_cn` text NOT NULL,
  `rules_zh_tw` text NOT NULL,
  `options` json NOT NULL COMMENT '选项：[{label:{en,zhCN,zhTW}, winCondition:{en,zhCN,zhTW}}]，顺序固定（FOMC：降息、维持、加息；CPI：高于、等于、低于）',
  `source_primary` varchar(300) NOT NULL,
  `source_backup` varchar(300) NOT NULL,
  `version` int NOT NULL DEFAULT '1',
  `updated_by` bigint DEFAULT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='经济日历的题型模板（S15，自动化需求第 3.2 节）';

INSERT INTO `content_template` (`code`, `title_en`, `title_zh_cn`, `title_zh_tw`, `rules_en`, `rules_zh_cn`, `rules_zh_tw`, `options`, `source_primary`, `source_backup`, `updated_at`) VALUES
('FOMC',
 'Fed {periodEn} meeting: will the federal funds target range be cut, held or raised?',
 '美联储 {periodZh} 议息：联邦基金利率目标区间会降、维持还是加？',
 '聯準會 {periodZh} 議息：聯邦基金利率目標區間會降、維持還是升？',
 'Resolved by the upper bound of the federal funds target range in the FOMC statement as first published on federalreserve.gov, compared with the upper bound before this meeting{prevEn}: lower = Cut, unchanged = Hold, higher = Raise. The statement is scheduled for {releaseAt}; betting closes 1 hour before. Later revisions do not count. If no statement is published within 48 hours of the scheduled time, the market is voided and all stakes are refunded. Resolved by the platform with a 24-hour publicity period.',
 '以美联储官网（federalreserve.gov）首次发布的本次议息会议声明里的联邦基金利率目标区间上限为准，和本次会议前的上限{prevZh}比较：变低 = 降息，不变 = 维持，变高 = 加息。声明计划发布时间 {releaseAt}，截止下注时间为发布前 1 小时。之后的修正不算。原定时间后 48 小时内没有发布声明的，本市场作废、全额退款。平台裁决，公示 24 小时。',
 '以聯準會官網（federalreserve.gov）首次發布的本次議息會議聲明裡的聯邦基金利率目標區間上限為準，和本次會議前的上限{prevTw}比較：變低 = 降息，不變 = 維持，變高 = 升息。聲明計畫發布時間 {releaseAt}，截止下注時間為發布前 1 小時。之後的修正不算。原定時間後 48 小時內沒有發布聲明的，本市場作廢、全額退款。平台裁決，公示 24 小時。',
 '[{"label":{"en":"Cut","zhCN":"降息","zhTW":"降息"},"winCondition":{"en":"The upper bound is lower than before the meeting","zhCN":"目标区间上限比会议前低","zhTW":"目標區間上限比會議前低"}},{"label":{"en":"Hold","zhCN":"维持","zhTW":"維持"},"winCondition":{"en":"The upper bound is unchanged","zhCN":"目标区间上限不变","zhTW":"目標區間上限不變"}},{"label":{"en":"Raise","zhCN":"加息","zhTW":"升息"},"winCondition":{"en":"The upper bound is higher than before the meeting","zhCN":"目标区间上限比会议前高","zhTW":"目標區間上限比會議前高"}}]',
 'https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm',
 'https://www.federalreserve.gov/monetarypolicy/openmarket.htm',
 UTC_TIMESTAMP(3)),
('CPI',
 'US {periodEn} CPI year-over-year: above, equal to or below {x}%?',
 '美国 {periodZh} CPI 同比：高于、等于还是低于 {x}%？',
 '美國 {periodZh} CPI 年增率：高於、等於還是低於 {x}%？',
 'Resolved by the year-over-year change of the CPI-U (not seasonally adjusted, rounded to one decimal place) for {periodEn} as first published by the U.S. Bureau of Labor Statistics (bls.gov), compared with {x}% (the value published for the previous month): Above / Equal / Below. The release is scheduled for {releaseAt}; betting closes 1 hour before. Later revisions do not count. If the release is postponed by more than 48 hours, the market is voided and all stakes are refunded. Resolved by the platform with a 24-hour publicity period.',
 '以美国劳工统计局（bls.gov）首次公布的 {periodZh} CPI-U 同比涨幅（未季调，保留 1 位小数）为准，和 {x}%（上一个月公布的值）比较：高于 / 等于 / 低于。公布计划时间 {releaseAt}，截止下注时间为公布前 1 小时。之后的修正不算。公布推迟超过 48 小时的，本市场作废、全额退款。平台裁决，公示 24 小时。',
 '以美國勞工統計局（bls.gov）首次公布的 {periodZh} CPI-U 年增率（未季調，保留 1 位小數）為準，和 {x}%（上一個月公布的值）比較：高於 / 等於 / 低於。公布計畫時間 {releaseAt}，截止下注時間為公布前 1 小時。之後的修正不算。公布延後超過 48 小時的，本市場作廢、全額退款。平台裁決，公示 24 小時。',
 '[{"label":{"en":"Above {x}%","zhCN":"高于 {x}%","zhTW":"高於 {x}%"},"winCondition":{"en":"The first published value is above {x}%","zhCN":"首次公布的值高于 {x}%","zhTW":"首次公布的值高於 {x}%"}},{"label":{"en":"Equal to {x}%","zhCN":"等于 {x}%","zhTW":"等於 {x}%"},"winCondition":{"en":"The first published value equals {x}%","zhCN":"首次公布的值等于 {x}%","zhTW":"首次公布的值等於 {x}%"}},{"label":{"en":"Below {x}%","zhCN":"低于 {x}%","zhTW":"低於 {x}%"},"winCondition":{"en":"The first published value is below {x}%","zhCN":"首次公布的值低于 {x}%","zhTW":"首次公布的值低於 {x}%"}}]',
 'https://www.bls.gov/cpi/',
 'https://www.bls.gov/schedule/news_release/cpi.htm',
 UTC_TIMESTAMP(3));

CREATE TABLE `content_econ_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `kind` varchar(8) NOT NULL COMMENT 'FOMC 利率决议 / CPI 美国 CPI 公布（EQ-6）',
  `period` varchar(7) NOT NULL COMMENT 'YYYY-MM：FOMC 是会议所在月份，CPI 是数据所属月份',
  `release_at` datetime(3) NOT NULL COMMENT '声明 / 数据计划发布时间（UTC）；截止下注 = 这个时间 − 1 小时',
  `prev_value` varchar(16) DEFAULT NULL COMMENT 'CPI：上一个月公布的同比（X，必填）；FOMC：会议前的目标区间上限（可选，写进规则）',
  `source_url` varchar(300) DEFAULT NULL COMMENT '日程的一手页面（运营录入时看的）',
  `status` varchar(12) NOT NULL DEFAULT 'SCHEDULED' COMMENT 'SCHEDULED 等待生成 / DRAFTED 已生成草稿 / FILTERED 被过滤 / FAILED 生成失败 / CANCELLED 取消 / EXPIRED 来不及生成',
  `market_no` bigint DEFAULT NULL COMMENT '生成的市场草稿编号',
  `note` varchar(500) DEFAULT NULL COMMENT '失败、过滤的原因',
  `created_by` bigint DEFAULT NULL,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  `version` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_content_econ_event` (`kind`, `release_at`),
  KEY `idx_content_econ_due` (`status`, `release_at`),
  CONSTRAINT `ck_content_econ_kind` CHECK ((`kind` in (_utf8mb4'FOMC', _utf8mb4'CPI')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='经济日历（S15：FOMC、美国 CPI；事件前 7 天自动生成市场草稿）';

CREATE TABLE `content_filter_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `source` varchar(12) NOT NULL COMMENT 'SPORTS 赛程 / ECON 经济日历',
  `ref` varchar(64) NOT NULL COMMENT '来源里的编号（赛程编号、经济日历 id）',
  `title` varchar(300) NOT NULL,
  `reason` varchar(16) NOT NULL COMMENT 'CATEGORY 品类不在白名单 / KEYWORD 命中敏感词',
  `matched` varchar(200) NOT NULL DEFAULT '' COMMENT '命中的词或品类',
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_content_filter` (`source`, `ref`),
  KEY `idx_content_filter_time` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='被敏感题过滤掉的事件（S15，"已过滤"日志；同一个来源编号只记一次）';

CREATE TABLE `content_draft` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `draft_no` bigint NOT NULL COMMENT '对外编号（雪花 ID）',
  `group_no` bigint NOT NULL COMMENT '同一次生成的中文、英文两条相同',
  `kind` varchar(16) NOT NULL COMMENT 'PREVIEW 赛前分析 / BRIEF 行情短文 / SEO_INTRO 页面说明 / ARTICLE 文章 / POST_X / POST_TG 帖子 / REPLY 回复草稿（只复制）',
  `lang` varchar(5) NOT NULL COMMENT 'zh / en（MQ-8：各自起草，不是互相翻译）',
  `title` varchar(300) NOT NULL DEFAULT '',
  `body` text NOT NULL,
  `refs` json NOT NULL COMMENT '引用的数据：[{key, label, value, source, at}]，数字核对按它',
  `checks` json DEFAULT NULL COMMENT '检查结果：数字核对（对不上的数字和位置）、禁用词、倾向词、Polymarket 说明',
  `check_ok` tinyint(1) NOT NULL DEFAULT '0',
  `status` varchar(12) NOT NULL COMMENT 'GENERATING / PENDING 待审核 / PUBLISHED 已发布（帖子：已审核，可复制）/ REJECTED / EXPIRED / FAILED 生成失败',
  `fail_reason` varchar(500) DEFAULT NULL,
  `reject_reason` varchar(500) DEFAULT NULL,
  `market_no` bigint DEFAULT NULL,
  `page_slug` varchar(80) DEFAULT NULL,
  `topic` varchar(300) DEFAULT NULL COMMENT '文章、回复的题目（Amos 给的）',
  `expires_at` datetime(3) DEFAULT NULL COMMENT '过期以后不能发布：赛前分析到开赛、行情和帖子 24 小时',
  `ai_calls` int NOT NULL DEFAULT '0',
  `created_by` bigint DEFAULT NULL COMMENT '手动触发的管理员；定时生成的为空',
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  `edited_by` bigint DEFAULT NULL,
  `published_by` bigint DEFAULT NULL,
  `published_at` datetime(3) DEFAULT NULL,
  `version` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_content_draft_no` (`draft_no`),
  UNIQUE KEY `uk_content_draft_lang` (`group_no`, `lang`),
  KEY `idx_content_draft_status` (`status`, `created_at`),
  KEY `idx_content_draft_market` (`market_no`, `kind`),
  KEY `idx_content_draft_expire` (`status`, `expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='草稿箱（S15：Gemini 起草，Amos 审核后才发布，EQ-1）';

CREATE TABLE `content_page` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `slug` varchar(80) NOT NULL COMMENT '地址 /insights/{slug}',
  `kind` varchar(12) NOT NULL COMMENT 'FOCUS 焦点战页 / BTC_STATS BTC 每小时涨跌统计 / ARTICLE 文章',
  `market_no` bigint DEFAULT NULL COMMENT '焦点战页的市场',
  `title_en` varchar(300) NOT NULL,
  `title_zh_cn` varchar(300) NOT NULL,
  `title_zh_tw` varchar(300) NOT NULL,
  `intro_en` text COMMENT '说明文字（审核后才有）',
  `intro_zh_cn` text,
  `intro_zh_tw` text COMMENT '由简体按固定规则转换（EX-9）',
  `body_en` mediumtext COMMENT '文章正文（审核后才有）',
  `body_zh_cn` mediumtext,
  `body_zh_tw` mediumtext,
  `data` json DEFAULT NULL COMMENT '数据部分（BTC 统计的快照）；焦点战页的数据实时取市场',
  `data_at` datetime(3) DEFAULT NULL,
  `status` varchar(8) NOT NULL DEFAULT 'VISIBLE' COMMENT 'VISIBLE / HIDDEN',
  `created_day` date NOT NULL COMMENT '新建的 UTC 日期（每天新增页面上限，EQ-7）',
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_content_page_slug` (`slug`),
  UNIQUE KEY `uk_content_page_market` (`market_no`),
  KEY `idx_content_page_day` (`created_day`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='网站上的 SEO 页面（S15，MF-11：数据部分自动，文字审核后才显示）';

CREATE TABLE `content_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `action` varchar(24) NOT NULL COMMENT 'GENERATE / PUBLISH / REJECT / COPY / EDIT / RETRY / EXPIRE / FILTER / ECON_DRAFT / PAGE / BLOCKED / ALERT / SETTING / WORD / STOP / RESUME / DIGEST',
  `kind` varchar(16) DEFAULT NULL,
  `ref` varchar(80) DEFAULT NULL COMMENT '草稿编号、页面地址、经济日历 id、市场编号',
  `lang` varchar(5) DEFAULT NULL,
  `outcome` varchar(12) NOT NULL COMMENT 'OK / FAILED / BLOCKED / SKIPPED',
  `detail` varchar(1000) NOT NULL DEFAULT '',
  `ai_calls` int NOT NULL DEFAULT '0',
  `admin_id` bigint DEFAULT NULL COMMENT '操作人；系统为空',
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_content_log_time` (`created_at`),
  KEY `idx_content_log_action` (`action`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='内容中心的日志（S15：每次生成、发布、驳回、复制、过滤、拦截；只增不改）';

CREATE TABLE `content_ai_day` (
  `day` date NOT NULL COMMENT 'UTC 日期',
  `used` int NOT NULL DEFAULT '0' COMMENT '当天的 Gemini 调用次数（每次 HTTP 请求算一次，含重试）',
  `content_used` int NOT NULL DEFAULT '0' COMMENT '其中文章起草用的',
  `score_used` int NOT NULL DEFAULT '0' COMMENT '其中比分解析（体育抓取，第一期没有）用的',
  `failures` int NOT NULL DEFAULT '0',
  `consecutive_failures` int NOT NULL DEFAULT '0',
  `tokens_in` bigint NOT NULL DEFAULT '0' COMMENT 'Gemini 返回的输入 token 数（没有时按字数估）',
  `tokens_out` bigint NOT NULL DEFAULT '0',
  `alerts` varchar(200) NOT NULL DEFAULT '' COMMENT '当天已经发过的告警（逗号分隔），每种每天最多一封',
  PRIMARY KEY (`day`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Gemini 每天的用量（S15：上限按条件更新计数，体育抓取优先，EQ-5）';

-- 员工邮件模板（收件人：有 content:publish 的在职管理员，SQ-19 下就是超管；没有人时发告警邮箱）
INSERT INTO `notify_template` (`code`, `audience`, `req_no`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `title_tw`, `body_tw`, `variables`, `channels`, `staff_perms`, `built_in`, `enabled`) VALUES ('CONTENT_ALERT', 'STAFF', NULL, NULL, '内容中心：Gemini 告警', 'Content center: Gemini alert', '内容中心：{what}', 'Content center: {whatEn}', '自动化程序的 Gemini 调用出现情况：{what}\n说明：{detail}\n今天已调用 {used} 次，上限 {limit} 次。\n文章、帖子草稿暂时不能由 AI 起草；卡片、数据页、市场草稿照常。请到后台"内容中心 → 用量和设置"查看。同一种情况每天最多发一封。', 'The automation program reported a Gemini issue: {whatEn}\nDetails: {detailEn}\nCalls today: {used} of {limit}.\nAI drafting of articles and posts is paused; cards, data pages and market drafts keep working. See Content center → Usage and settings in the admin console. At most one email per issue per day.', '', '', 'what,whatEn,detail,detailEn,used,limit', 'EMAIL', 'content:publish', 1, 1);
INSERT INTO `notify_template` (`code`, `audience`, `req_no`, `kind`, `name_zh`, `name_en`, `title_zh`, `title_en`, `body_zh`, `body_en`, `title_tw`, `body_tw`, `variables`, `channels`, `staff_perms`, `built_in`, `enabled`) VALUES ('CONTENT_DIGEST', 'STAFF', NULL, NULL, '每日草稿包', 'Daily draft pack', '每日草稿包 {day}：待审核 {pending} 条', 'Daily draft pack {day}: {pending} awaiting review', '今天（{day} UTC）的草稿包：\n待审核：{pending} 条\n生成失败：{failed} 条\n经济日历的市场草稿：{econ} 个（要有上架权限的人检查后上架）\n焦点战建议：{focus}\nGemini 今天已调用 {used} 次，上限 {limit} 次。\n所有 AI 起草的内容都要你审核后才发布；X、Telegram 的帖子只出草稿，审核后复制、手动发。请到后台"内容中心 → 草稿箱"。', 'Today''s draft pack ({day} UTC):\nAwaiting review: {pending}\nFailed: {failed}\nEconomic calendar market drafts: {econ} (someone with the listing permission must check and list them)\nFocus match suggestions: {focus}\nGemini calls today: {used} of {limit}.\nEverything drafted by AI is published only after your review; X and Telegram posts are drafts only: approve, copy and post them yourself. See Content center → Drafts in the admin console.', '', '', 'day,pending,failed,econ,focus,used,limit', 'EMAIL', 'content:publish', 1, 1);

-- 菜单"内容中心"（需求第 6.1 节）；和 Menus.java、web/admin/src/config/menu.tsx 一致
INSERT IGNORE INTO `admin_menu` (`menu_key`, `sort_order`, `visible`) VALUES
  ('content', 57, 1),
  ('content-drafts', 10, 1),
  ('content-events', 20, 1),
  ('content-pages', 30, 1),
  ('content-templates', 40, 1),
  ('content-tools', 50, 1),
  ('content-logs', 60, 1),
  ('content-settings', 70, 1);
