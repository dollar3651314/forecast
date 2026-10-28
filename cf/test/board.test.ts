/**
 * 看板（forecast S19，P13）：口径和后端 S16 的 BtcBoard 一样（S16BoardTest 同一段 K 线：24 根开盘 100，15 根收涨，62.5% / 37.5%，现价 65432.10）；
 * OKX、币安两种格式；OKX 取不到换币安；都取不到保留上一次的数据；从没取到过时"暂时取不到"；页面接口只读缓存、不调外部接口；健康检查里每个源的结果。
 */
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { BINANCE, board, cached, compute, health, OKX, pct, refresh, resetMemo, round2, STALE_MS, type Candle } from '../src/board';
import { newDb, stubFetch, testEnv } from './helpers';

let dispose: () => Promise<void>;
let db: D1Database;
beforeAll(async () => {
  ({ dispose, db } = await newDb());
});
afterAll(async () => {
  await dispose();
});
afterEach(() => {
  vi.unstubAllGlobals();
  resetMemo();
});

const H = 3_600_000;
const NOW = Date.parse('2026-10-28T08:20:00.000Z');
const HOUR = Date.parse('2026-10-28T08:00:00.000Z');
// 和后端 S16BoardTest.day() 一样
const CLOSES = ['101', '99', '100', '102.5', '98', '100.01', '99.99', '100', '105', '95', '100', '100', '101', '102', '97', '96', '100.5', '99.5', '103', '100', '98.7', '101.2', '100', '90'];

function binanceBody(price: string): unknown[] {
  const rows: unknown[] = CLOSES.map((c, i) => {
    const open = HOUR - (24 - i) * H;
    return [open, '100', '0', '0', c, '1', open + H - 1, '1', 1, '0', '0', '0'];
  });
  rows.push([HOUR, price, '0', '0', price, '1', HOUR + H - 1, '1', 1, '0', '0', '0']);
  return rows;
}

function okxBody(price: string): unknown {
  const rows = CLOSES.map((c, i) => [String(HOUR - (24 - i) * H), '100', '0', '0', c, '1', '1', '1', '1']);
  rows.push([String(HOUR), price, '0', '0', price, '1', '1', '1', '0']);
  return { code: '0', msg: '', data: rows.reverse() };
}

const ok = (body: unknown) => () => new Response(JSON.stringify(body), { status: 200 });

describe('口径（和 S16 一样）', () => {
  it('同一段 K 线：涨 15 跌 9，62.5% / 37.5%，没收盘的那一根不算，现价是最新一根', () => {
    const s = compute(BINANCE.parse(binanceBody('65432.1')), NOW, 'Binance');
    expect(s).toMatchObject({ hours: 24, up: 15, down: 9, upPct: '62.5', downPct: '37.5', price: '65432.10', source: 'Binance' });
    expect(s.from).toBe(new Date(HOUR - 24 * H).toISOString());
    expect(s.to).toBe(new Date(HOUR).toISOString());
  });

  it('OKX 的格式（新的在前、字符串时间）算出来一样', () => {
    const a = compute(OKX.parse(okxBody('65432.1')), NOW, 'OKX');
    const b = compute(BINANCE.parse(binanceBody('65432.1')), NOW, 'Binance');
    expect({ ...a, source: '' }).toEqual({ ...b, source: '' });
  });

  it('收盘时间等于现在的那一根还不算收盘（收盘时间早于现在才算，和后端一样）', () => {
    const c: Candle[] = [
      { openTime: 0, open: '1', close: '2', closeTime: H - 1 },
      { openTime: H, open: '2', close: '1', closeTime: 2 * H - 1 },
    ];
    expect(compute(c, 2 * H - 1, 'x').hours).toBe(1);
    expect(compute(c, 2 * H, 'x')).toMatchObject({ hours: 2, up: 1, down: 1 });
  });

  it('比例 1 位小数四舍五入、价格 2 位小数四舍五入（不经过浮点数）', () => {
    expect(pct(15, 24)).toBe('62.5');
    expect(pct(1, 16)).toBe('6.3');
    expect(pct(1, 24)).toBe('4.2');
    expect(pct(24, 24)).toBe('100.0');
    expect(pct(0, 24)).toBe('0.0');
    expect(round2('81434.03000000')).toBe('81434.03');
    expect(round2('99.995')).toBe('100.00');
    expect(round2('0.004')).toBe('0.00');
    expect(round2('0.005')).toBe('0.01');
    expect(round2('65432.1')).toBe('65432.10');
    expect(round2('7')).toBe('7.00');
  });

  it('格式不对的返回直接报错（不会算出错的数）', () => {
    expect(() => OKX.parse({ code: '51001', data: [] })).toThrow();
    expect(() => BINANCE.parse({ code: 0, msg: 'restricted location' })).toThrow();
    expect(() => BINANCE.parse([[1, 'abc', 0, 0, '1', 0, 2]])).toThrow();
  });
});

describe('定时取数和缓存', () => {
  it('OKX 能用就用 OKX；页面接口带来源和更新时间，不调外部接口', async () => {
    const s = stubFetch({ 'https://www.okx.com/': ok(okxBody('70000')) });
    vi.stubGlobal('fetch', s.fn);
    expect(await refresh(testEnv(db), NOW)).toBe('OKX');
    expect(s.calls).toEqual([OKX.url]);
    s.calls.length = 0;
    const res = await board(testEnv(db), NOW + 1000);
    expect(s.calls).toHaveLength(0);
    expect(res.headers.get('Cache-Control')).toBe('public, max-age=30');
    const b = (await res.json()) as { btc: Record<string, unknown>; launched: boolean; waitlistOpen: boolean; slate: unknown; polymarket: unknown; telegram: unknown };
    expect(b.btc).toMatchObject({ available: true, price: '70000.00', up: 15, down: 9, upPct: '62.5', source: 'OKX', updatedAt: new Date(NOW).toISOString() });
    expect(b).toMatchObject({ launched: false, waitlistOpen: true, slate: { available: false, items: [], next: null }, polymarket: { enabled: false } });
    expect(b.telegram).toEqual({ channelZh: null, channelEn: null, bot: null });
  });

  it('OKX 取不到（超时、5xx、格式不对）换币安 data-api；健康检查记下每个源的结果', async () => {
    const s = stubFetch({ 'https://www.okx.com/': () => new Response('bad gateway', { status: 502 }), 'https://data-api.binance.vision/': ok(binanceBody('71000')) });
    vi.stubGlobal('fetch', s.fn);
    expect(await refresh(testEnv(db), NOW + H)).toBe('Binance');
    resetMemo();
    expect((await cached(testEnv(db), NOW + H))!.source).toBe('Binance');
    const h = await health(testEnv(db), NOW + H);
    expect(h.status).toBe('ok');
    expect(h.source).toBe('Binance');
    expect(h.sources.find((x) => x.source === 'OKX')).toMatchObject({ ok: false, error: 'HTTP 502', failures: 1 });
    expect(h.sources.find((x) => x.source === 'Binance')).toMatchObject({ ok: true, failures: 0 });
  });

  it('两个都取不到（例如 451）：保留上一次的数据和它的更新时间；超过 30 分钟健康检查是 stale', async () => {
    const s = stubFetch({
      'https://www.okx.com/': () => new Response('{}', { status: 403 }),
      'https://data-api.binance.vision/': () => new Response('{"code":0,"msg":"restricted"}', { status: 451 }),
    });
    vi.stubGlobal('fetch', s.fn);
    const t = NOW + H + STALE_MS + 1000;
    expect(await refresh(testEnv(db), t)).toBeNull();
    resetMemo();
    const b = (await (await board(testEnv(db), t)).json()) as { btc: { price: string; updatedAt: string } };
    expect(b.btc.price).toBe('71000.00');
    expect(b.btc.updatedAt).toBe(new Date(NOW + H).toISOString());
    const h = await health(testEnv(db), t);
    expect(h.status).toBe('stale');
    expect(h.sources.find((x) => x.source === 'Binance')).toMatchObject({ ok: false, error: 'HTTP 451', failures: 1 });
  });

  it('同一个实例 30 秒内复用读到的缓存', async () => {
    const env = testEnv(db);
    const first = await cached(env, NOW);
    await db.prepare("UPDATE board_cache SET snapshot = json_set(snapshot, '$.price', '1.00')").run();
    expect((await cached(env, NOW + 29_999))!.price).toBe(first!.price);
    expect((await cached(env, NOW + 30_000))!.price).toBe('1.00');
  });

  it('Telegram 链接只认 https 开头的', async () => {
    const res = await board(testEnv(db, { TG_CHANNEL_ZH: 'https://t.me/quickcome_cn', TG_BOT: 'javascript:alert(1)' }), NOW);
    expect(((await res.json()) as { telegram: unknown }).telegram).toEqual({ channelZh: 'https://t.me/quickcome_cn', channelEn: null, bot: null });
  });
});

describe('从没取到过', () => {
  it('页面显示"暂时取不到"（available=false），健康检查 down', async () => {
    const fresh = await newDb();
    try {
      const b = (await (await board(testEnv(fresh.db), NOW)).json()) as { btc: { available: boolean } };
      expect(b.btc.available).toBe(false);
      expect((await health(testEnv(fresh.db), NOW)).status).toBe('down');
    } finally {
      await fresh.dispose();
    }
  });
});
