/** 展示工具：截断地址和哈希、按语言取名称、时间格式化 */
export function shortHash(value: string | null | undefined, head = 6, tail = 4): string {
  if (!value) return '—';
  if (value.length <= head + tail + 1) return value;
  return `${value.slice(0, head)}…${value.slice(-tail)}`;
}

export function localName(lang: string, zh: string, en: string): string {
  return lang.startsWith('zh') ? zh : en;
}

/** 后台统一按 UTC+8 显示时间 */
const DISPLAY_OFFSET_MS = 8 * 3600_000;
const ISO_RE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:?\d{2})$/;

/**
 * 完整时间 "YYYY-MM-DD HH:mm:ss"（UTC+8）。
 * 真实接口返回 ISO UTC 字符串（"2026-10-14T01:12:33Z"），在这里换算；模拟数据本来就是这个格式，原样返回。
 */
export function fullTime(value: string | null | undefined): string {
  if (!value) return '—';
  if (!ISO_RE.test(value)) return value;
  const ms = Date.parse(value);
  if (Number.isNaN(ms)) return value;
  return new Date(ms + DISPLAY_OFFSET_MS).toISOString().slice(0, 19).replace('T', ' ');
}

/** "2026-10-14 09:12:33"（或 ISO UTC）→ "10-14 09:12"（UTC+8） */
export function shortTime(value: string | null | undefined): string {
  if (!value) return '—';
  const full = fullTime(value);
  const m = /^\d{4}-(\d{2}-\d{2}) (\d{2}:\d{2})/.exec(full);
  return m ? `${m[1]} ${m[2]}` : full;
}

/** 超过这么多秒没有新报价就标红（v2 E6，需求说明书 11.1） */
export const QUOTE_STALE_SECONDS = 60;

export type QuoteFreshness = 'none' | 'fresh' | 'stale' | 'closed' | 'disabled';

/**
 * 品种的报价新鲜度（v2 E6）：停用的品种不看；从没收到过报价是 none（显示"暂无报价"，开市时也标红）；
 * 休市时没有新报价是正常的（closed，不标红）；开市时超过 1 分钟没报价是 stale（标红）。
 */
export function quoteFreshness(s: { enabled: boolean; lastQuoteAt?: string | null; tradingOpen?: boolean }, nowMs: number = Date.now()): QuoteFreshness {
  if (!s.enabled) return 'disabled';
  if (!s.lastQuoteAt) return 'none';
  if (s.tradingOpen === false) return 'closed';
  const at = Date.parse(s.lastQuoteAt);
  if (Number.isNaN(at)) return 'none';
  return nowMs - at > QUOTE_STALE_SECONDS * 1000 ? 'stale' : 'fresh';
}
