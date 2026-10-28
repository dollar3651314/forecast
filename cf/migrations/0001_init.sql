-- forecast S19：Cloudflare 上的公开部分（等候页、看板、来源统计）的 D1 表。迁移只往前走（Cloudflare 规范 K4）：以后改表另加 0002…，并兼容上一版代码。
-- 时间一律是 UTC 的 ISO 字符串（毫秒，结尾 Z，例如 2026-10-28T08:00:00.000Z），按字符串比较就是按时间比较。

-- 1. 等候页留的邮箱（和后端 growth_waitlist 同样的含义，开放交易时导入后端，P15）。双重确认：PENDING 待确认 → CONFIRMED 已确认；任何时候都能退订（UNSUBSCRIBED）。
--    同一邮箱一行（小写）；确认链接 7 天有效（confirm_sent_at 起算）；同一邮箱两封确认邮件至少隔 10 分钟、最多 5 封（confirm_sends）；IP 只存 sha256。
--    没有 NOTIFIED：上线通知由后端 S16 发（名单导过去以后）。
CREATE TABLE waitlist (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  email TEXT NOT NULL UNIQUE,
  lang TEXT NOT NULL CHECK (lang IN ('en', 'zh-CN', 'zh-TW')),
  status TEXT NOT NULL CHECK (status IN ('PENDING', 'CONFIRMED', 'UNSUBSCRIBED')),
  confirm_token TEXT NOT NULL UNIQUE,
  confirm_sent_at TEXT NOT NULL,
  confirm_sends INTEGER NOT NULL DEFAULT 1,
  unsub_token TEXT NOT NULL UNIQUE,
  src TEXT,
  cmp TEXT,
  ref TEXT,
  start_param TEXT,
  ip_hash TEXT NOT NULL,
  created_at TEXT NOT NULL,
  confirmed_at TEXT,
  unsubscribed_at TEXT,
  updated_at TEXT NOT NULL
);
CREATE INDEX idx_waitlist_status ON waitlist (status, id);

-- 2. 按 IP 限次（固定窗口；key 是 IP 的 sha256）。定时任务删两小时以前的窗口。
CREATE TABLE rate_limit (
  bucket TEXT NOT NULL,
  key TEXT NOT NULL,
  window_start INTEGER NOT NULL,
  count INTEGER NOT NULL,
  PRIMARY KEY (bucket, key, window_start)
);

-- 3. 看板的行情缓存（只有一行，id = 1）：定时任务写，页面读。
CREATE TABLE board_cache (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  snapshot TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

-- 4. 每个行情源最近一次的结果（健康检查用：部署后看 Cloudflare 出口能不能访问 OKX、币安）。
CREATE TABLE source_status (
  source TEXT PRIMARY KEY,
  ok INTEGER NOT NULL,
  error TEXT,
  failures INTEGER NOT NULL DEFAULT 0,
  checked_at TEXT NOT NULL,
  last_ok_at TEXT
);

-- 5. 带来源参数的访问，按天 + src + cmp 计数（P14，算"访问 → 订阅"的转化；不记 IP、设备号）。没有的值存空串。
CREATE TABLE visit_daily (
  day TEXT NOT NULL,
  src TEXT NOT NULL,
  cmp TEXT NOT NULL,
  count INTEGER NOT NULL,
  PRIMARY KEY (day, src, cmp)
);
