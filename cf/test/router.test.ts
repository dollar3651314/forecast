/**
 * 路由（forecast S19，src/index.ts）：只有列出来的公开接口；交易和登录的接口一律 404（P11 底线）；不是 /api/ 的交给静态资源；
 * 出错返回统一的 500；定时任务取行情并删过期的限次计数。整条真实运行时的路径另外由 scripts/e2e-local.sh（wrangler dev）跑。
 */
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import worker from '../src/index';
import { OKX, resetMemo } from '../src/board';
import { newDb, post, stubFetch, testCtx, testEnv } from './helpers';

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
});

const call = (req: Request, env = testEnv(db)) => worker.fetch!(req as Request<unknown, IncomingRequestCfProperties>, env, testCtx());
const get = (p: string) => new Request(`https://www.example.test${p}`);

describe('路由', () => {
  it('交易、登录、后台、端到端的接口都不存在（404）', async () => {
    for (const p of ['/api/auth/login', '/api/auth/register', '/api/me/session', '/api/markets', '/api/bets', '/api/wallet/deposit-address', '/api/admin/waitlist/launch', '/api/e2e/clock', '/api/ws/user']) {
      expect((await call(get(p))).status, p).toBe(404);
      expect((await call(post(p, {}))).status, p).toBe(404);
    }
  });

  it('公开接口能用，带安全头；写接口用 GET 访问是 404', async () => {
    const res = await call(get('/api/public/board'));
    expect(res.status).toBe(200);
    expect(res.headers.get('X-Content-Type-Options')).toBe('nosniff');
    expect(res.headers.get('X-Frame-Options')).toBe('DENY');
    expect((await call(get('/api/health'))).status).toBe(200);
    expect((await call(get('/api/public/pages/how-to-play?lang=zh-TW'))).status).toBe(200);
    expect((await call(get('/api/public/waitlist'))).status).toBe(404);
  });

  it('不是 /api/ 的交给静态资源（页面、官网）', async () => {
    const res = await call(get('/about/terms'));
    expect(await res.text()).toBe('asset');
  });

  it('接口出错：500 INTERNAL_ERROR，不把错误细节给出去', async () => {
    const broken = { prepare: () => { throw new Error('secret detail'); } } as unknown as D1Database;
    resetMemo();
    const res = await call(get('/api/public/board'), testEnv(broken));
    expect(res.status).toBe(500);
    const text = await res.text();
    expect(text).toContain('INTERNAL_ERROR');
    expect(text).not.toContain('secret detail');
  });
});

describe('定时任务', () => {
  it('取行情写进缓存，删掉两小时以前的限次计数', async () => {
    const H = 3_600_000;
    const hour = Math.floor(Date.now() / H) * H;
    const rows = Array.from({ length: 25 }, (_, i) => [String(hour - (24 - i) * H), '100', '0', '0', i % 2 ? '101' : '99', '1', '1', '1', i === 24 ? '0' : '1']).reverse();
    const s = stubFetch({ 'https://www.okx.com/': () => new Response(JSON.stringify({ code: '0', data: rows }), { status: 200 }) });
    vi.stubGlobal('fetch', s.fn);
    await db.prepare("INSERT INTO rate_limit (bucket, key, window_start, count) VALUES ('waitlist', 'old', 0, 3), ('waitlist', 'new', ?1, 1)").bind(hour).run();
    const ctx = testCtx();
    await worker.scheduled!({ cron: '*/5 * * * *', scheduledTime: Date.now(), noRetry() {} } as ScheduledController, testEnv(db), ctx);
    await ctx.flush();
    expect(s.calls).toEqual([OKX.url]);
    const cache = await db.prepare('SELECT snapshot FROM board_cache WHERE id = 1').first<{ snapshot: string }>();
    expect(JSON.parse(cache!.snapshot)).toMatchObject({ source: 'OKX', hours: 24, up: 12, down: 12 });
    const keys = await db.prepare('SELECT key FROM rate_limit ORDER BY key').all<{ key: string }>();
    expect(keys.results.map((r) => r.key)).toEqual(['new']);
  });
});
