/**
 * 看板（forecast S19，P13）：BTC 现价和过去 24 小时每小时涨跌的比例，口径和后端 growth/board/BtcBoard.compute 一样（S16，AC-96）——
 * 最近 25 根 1 小时 K 线，只算已经收盘的（收盘时间早于现在）最后 24 根；收盘价 ≥ 开盘价算涨；比例 1 位小数四舍五入；现价是最新一根的收盘价（2 位小数）。
 * - **不在页面请求里取**：定时任务（Cron，每 5 分钟）取一次存进 D1 的 board_cache（一行），页面只读这一行（同一个 Worker 实例 30 秒内复用），
 *   每次请求的 CPU 很少（Free 每次请求 10 ms）。
 * - **数据源**：OKX 优先，取不到换币安 data-api.binance.vision（api.binance.com 在部分地区返回 451）；都取不到时保留上一次的数据和它的更新时间，
 *   从来没取到过时页面写"暂时取不到"。每个源最近一次的结果记在 source_status，健康检查里看得到（部署后看 Cloudflare 出口能不能访问，就看这里）。
 * - 返回的结构和后端 BoardService.Board 一样（页面不用改），btc 多一个 source（OKX / Binance），页面照实写来源。
 */
import type { Env } from './env';
import { json } from './http';
import { flag, iso } from './util';

export const HOURS = 24;
export const HOUR_MS = 60 * 60 * 1000;
export const STALE_MS = 30 * 60 * 1000;
export const FETCH_TIMEOUT_MS = 8000;

export interface Candle {
  openTime: number;
  open: string;
  close: string;
  /** 收盘时间（毫秒，这一根的最后 1 毫秒，和币安的 closeTime 一样） */
  closeTime: number;
}

export interface Snapshot {
  symbol: string;
  price: string;
  hours: number;
  up: number;
  down: number;
  upPct: string;
  downPct: string;
  from: string;
  to: string;
  fetchedAt: string;
  source: string;
}

export interface Source {
  name: string;
  url: string;
  parse(body: unknown): Candle[];
}

const DECIMAL = /^\d+(\.\d+)?$/;

function dec(v: unknown): string {
  const s = typeof v === 'number' ? String(v) : typeof v === 'string' ? v.trim() : '';
  if (!DECIMAL.test(s)) throw new Error('价格格式不对');
  return s;
}

/** OKX：GET /api/v5/market/candles，data 是新的在前，每根 [开盘时间, 开, 高, 低, 收, …, confirm] */
export const OKX: Source = {
  name: 'OKX',
  url: 'https://www.okx.com/api/v5/market/candles?instId=BTC-USDT&bar=1H&limit=25',
  parse(body) {
    const b = body as { code?: unknown; data?: unknown };
    if (!b || b.code !== '0' || !Array.isArray(b.data) || b.data.length === 0) throw new Error('OKX 返回的不是 K 线');
    return (b.data as unknown[])
      .map((k) => {
        if (!Array.isArray(k) || k.length < 5) throw new Error('K 线格式不对');
        const openTime = Number(k[0]);
        if (!Number.isFinite(openTime)) throw new Error('K 线时间不对');
        return { openTime, open: dec(k[1]), close: dec(k[4]), closeTime: openTime + HOUR_MS - 1 };
      })
      .sort((a, b2) => a.openTime - b2.openTime);
  },
};

/** 币安：GET /api/v3/klines，旧的在前，每根 [开盘时间, 开, 高, 低, 收, 量, 收盘时间, …] */
export const BINANCE: Source = {
  name: 'Binance',
  url: 'https://data-api.binance.vision/api/v3/klines?symbol=BTCUSDT&interval=1h&limit=25',
  parse(body) {
    if (!Array.isArray(body) || body.length === 0) throw new Error('币安返回的不是 K 线数组');
    return (body as unknown[]).map((k) => {
      if (!Array.isArray(k) || k.length < 7) throw new Error('K 线格式不对');
      const openTime = Number(k[0]);
      const closeTime = Number(k[6]);
      if (!Number.isFinite(openTime) || !Number.isFinite(closeTime)) throw new Error('K 线时间不对');
      return { openTime, open: dec(k[1]), close: dec(k[4]), closeTime };
    });
  },
};

export const SOURCES: Source[] = [OKX, BINANCE];

/** 十进制字符串保留 2 位小数，四舍五入（不经过浮点数，和后端 BigDecimal.setScale(2, HALF_UP) 一样） */
export function round2(s: string): string {
  const [i, f = ''] = s.split('.');
  const digits = (i + (f + '000').slice(0, 2)).replace(/^0+(?=\d)/, '');
  let n = BigInt(digits);
  if ((f[2] ?? '0') >= '5') n += 1n;
  const str = n.toString().padStart(3, '0');
  return `${str.slice(0, -2)}.${str.slice(-2)}`;
}

/** a × 100 ÷ total，1 位小数四舍五入（"62.5"、"100.0"、"0.0"） */
export function pct(a: number, total: number): string {
  const tenths = Math.floor((2 * a * 1000 + total) / (2 * total));
  return `${Math.floor(tenths / 10)}.${tenths % 10}`;
}

function cmpDec(a: string, b: string): number {
  // 价格都是正数的十进制字符串：补齐小数位后按整数比较，不经过浮点数
  const [ai, af = ''] = a.split('.');
  const [bi, bf = ''] = b.split('.');
  const len = Math.max(af.length, bf.length);
  const x = BigInt(ai + af.padEnd(len, '0'));
  const y = BigInt(bi + bf.padEnd(len, '0'));
  return x === y ? 0 : x > y ? 1 : -1;
}

/** 按口径算（单独拿出来，测试用和后端 S16BoardTest 同一段 K 线手算对照） */
export function compute(candles: Candle[], now: number, source: string, n = HOURS, symbol = 'BTCUSDT'): Snapshot {
  if (candles.length === 0) throw new Error('没有 K 线');
  const sorted = [...candles].sort((a, b) => a.openTime - b.openTime);
  const latest = sorted[sorted.length - 1];
  const closed = sorted.filter((k) => k.closeTime < now);
  const use = closed.slice(Math.max(0, closed.length - n));
  if (use.length === 0) throw new Error('没有已经收盘的 K 线');
  const up = use.filter((k) => cmpDec(k.close, k.open) >= 0).length;
  const down = use.length - up;
  return {
    symbol,
    price: round2(latest.close),
    hours: use.length,
    up,
    down,
    upPct: pct(up, use.length),
    downPct: pct(down, use.length),
    from: iso(use[0].openTime),
    to: iso(use[use.length - 1].closeTime + 1),
    fetchedAt: iso(now),
    source,
  };
}

async function fetchSource(s: Source, now: number): Promise<Snapshot> {
  const res = await fetch(s.url, { headers: { Accept: 'application/json', 'User-Agent': 'forecast-public/1' }, signal: AbortSignal.timeout(FETCH_TIMEOUT_MS) });
  if (res.status !== 200) throw new Error(`HTTP ${res.status}`);
  const text = await res.text();
  if (text.length > 256 * 1024) throw new Error('返回太大');
  return compute(s.parse(JSON.parse(text)), now, s.name);
}

/**
 * 定时任务：按顺序试每个源，第一个成功的写进 board_cache；每个试过的源都记一次结果。返回用了哪个源（都失败为 null）。
 */
export async function refresh(env: Env, now = Date.now()): Promise<string | null> {
  for (const s of SOURCES) {
    try {
      const snap = await fetchSource(s, now);
      await env.DB.batch([
        env.DB.prepare('INSERT INTO board_cache (id, snapshot, updated_at) VALUES (1, ?1, ?2) ON CONFLICT (id) DO UPDATE SET snapshot = excluded.snapshot, updated_at = excluded.updated_at').bind(
          JSON.stringify(snap),
          snap.fetchedAt,
        ),
        env.DB.prepare(
          'INSERT INTO source_status (source, ok, error, failures, checked_at, last_ok_at) VALUES (?1, 1, NULL, 0, ?2, ?2) ' +
            'ON CONFLICT (source) DO UPDATE SET ok = 1, error = NULL, failures = 0, checked_at = ?2, last_ok_at = ?2',
        ).bind(s.name, iso(now)),
      ]);
      memo = null;
      return s.name;
    } catch (e) {
      const msg = String((e as Error)?.message || e).slice(0, 200);
      console.log(JSON.stringify({ event: 'board.source_failed', source: s.name, error: msg }));
      await env.DB.prepare(
        'INSERT INTO source_status (source, ok, error, failures, checked_at) VALUES (?1, 0, ?2, 1, ?3) ' +
          'ON CONFLICT (source) DO UPDATE SET ok = 0, error = ?2, failures = failures + 1, checked_at = ?3',
      )
        .bind(s.name, msg, iso(now))
        .run();
    }
  }
  return null;
}

/** 同一个 Worker 实例里 30 秒内复用读到的缓存（少读 D1；数据本来就是每 5 分钟才变） */
let memo: { at: number; snap: Snapshot | null } | null = null;
const MEMO_MS = 30_000;

export function resetMemo(): void {
  memo = null;
}

export async function cached(env: Env, now = Date.now()): Promise<Snapshot | null> {
  if (memo && now - memo.at < MEMO_MS && now >= memo.at) return memo.snap;
  const row = await env.DB.prepare('SELECT snapshot FROM board_cache WHERE id = 1').first<{ snapshot: string }>();
  const snap = row ? (JSON.parse(row.snapshot) as Snapshot) : null;
  memo = { at: now, snap };
  return snap;
}

const link = (v: string | undefined) => (v && /^https:\/\/[^\s"'<>]+$/.test(v.trim()) ? v.trim() : null);

/** GET /api/public/board：结构同后端 BoardService.Board */
export async function board(env: Env, now = Date.now()): Promise<Response> {
  const s = await cached(env, now);
  const open = flag(env.WAITLIST_OPEN, true);
  const btc = s
    ? { available: true, symbol: s.symbol, price: s.price, hours: s.hours, up: s.up, down: s.down, upPct: s.upPct, downPct: s.downPct, from: s.from, to: s.to, updatedAt: s.fetchedAt, source: s.source }
    : { available: false, symbol: null, price: null, hours: 0, up: 0, down: 0, upPct: null, downPct: null, from: null, to: null, updatedAt: null, source: null };
  return json(
    {
      updatedAt: iso(now),
      btc,
      // 交易不在 Cloudflare 上：这里永远是"还没上线"；开放交易以后等候页关闭（waitlistOpen=false），场次去生产的客户端看
      launched: false,
      waitlistOpen: open,
      slate: { available: false, items: [], next: null },
      polymarket: { enabled: false },
      telegram: { channelZh: link(env.TG_CHANNEL_ZH), channelEn: link(env.TG_CHANNEL_EN), bot: link(env.TG_BOT) },
    },
    200,
    { 'Cache-Control': 'public, max-age=30' },
  );
}

export interface BoardHealth {
  status: 'ok' | 'stale' | 'down';
  updatedAt: string | null;
  ageSeconds: number | null;
  source: string | null;
  sources: { source: string; ok: boolean; error: string | null; failures: number; checkedAt: string; lastOkAt: string | null }[];
}

export async function health(env: Env, now = Date.now()): Promise<BoardHealth> {
  const row = await env.DB.prepare('SELECT snapshot, updated_at FROM board_cache WHERE id = 1').first<{ snapshot: string; updated_at: string }>();
  const st = await env.DB.prepare('SELECT source, ok, error, failures, checked_at, last_ok_at FROM source_status ORDER BY source').all<{
    source: string;
    ok: number;
    error: string | null;
    failures: number;
    checked_at: string;
    last_ok_at: string | null;
  }>();
  const age = row ? Math.max(0, Math.round((now - Date.parse(row.updated_at)) / 1000)) : null;
  const snap = row ? (JSON.parse(row.snapshot) as Snapshot) : null;
  return {
    status: age === null ? 'down' : age * 1000 > STALE_MS ? 'stale' : 'ok',
    updatedAt: row?.updated_at ?? null,
    ageSeconds: age,
    source: snap?.source ?? null,
    sources: (st.results ?? []).map((r) => ({ source: r.source, ok: r.ok === 1, error: r.error, failures: r.failures, checkedAt: r.checked_at, lastOkAt: r.last_ok_at })),
  };
}
