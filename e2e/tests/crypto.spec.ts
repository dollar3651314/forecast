/**
 * 加密市场自动化的端到端（forecast S6，技术方案/v1-架构方案.md 第 16 节 S6、第 17 节"替身数据源触发自动结算"；AC-27、AC-28 的主线）：接口级，页面在原型确认后做（S11）。
 * 超管启用默认的自动生成模板（BTC 1 小时、每天 13:00 UTC 一场，MQ-1 节目化）→ 生成并直接上架 → 两个客户下注（涨 / 跌）→ 窗口开始前 30 秒截止 →
 * 窗口结束 → 1 分钟后自动裁决（替身数据源：后端 e2e profile 下币安、OKX 的地址指向 /api/e2e/candles/**，走真实的适配器、网关、存档）→ 自动结算 →
 * 详情公开开始价、结束价和来源；收盘 = 开盘判涨（E9）。两种形态都跑；结算事务边界跟后端配置（默认 500，分批时用 /api/e2e/settle/tick 立即派完）。
 * S14：S13 部署形态偶发"原始返回多一组"的根因是后端每 15 秒的定时任务和用例的 /api/e2e/resolve/tick 同时取数（两边都存了一组，开发记录/S14-分享卡片.md 第 2 节）。
 * 后端改成取数前先认领这一次尝试；这里把替身的回复放慢 1.5 秒、同时发两个 tick（再加上随时可能到的定时任务），每次都制造并发，
 * 核对两个来源各只存了一行原始返回。
 */
import { expect, request, test, type APIRequestContext } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { STUB_URL } from './targets';
import { step } from './support';
import { adminSession, clientSession, fund, json } from './api';

type Autogen = { autogenNo: string; symbol: string; period: string; mode: string; enabled: boolean; upcoming: { windowStart: string; generated: boolean; marketNo: string | null }[] };
type Account = { available: string; betLocked: string };
const HOUR = 3_600_000;

async function now(anon: APIRequestContext): Promise<number> {
  return new Date((await json<{ now: string }>(await anon.get('/api/e2e/clock'), '读时钟')).now).getTime();
}

async function advanceTo(anon: APIRequestContext, target: number) {
  const ms = target - (await now(anon));
  if (ms > 0) await json(await anon.post('/api/e2e/clock/advance', { data: { millis: ms } }), `拨时钟 ${new Date(target).toISOString()}`);
}

test('加密：自动生成 → 下注 → 窗口结束 → 自动裁决（收盘 = 开盘判涨）→ 自动结算 → 详情公开开始价、结束价、来源', async () => {
  const anon = await request.newContext({ baseURL: STUB_URL });
  let autogenNo = '';
  try {
    // 下一个 13:00 UTC 的窗口：时钟拨到它开放（窗口开始前 3 小时）之后 1 分钟
    const window = await step('算下一个 13:00 UTC 的场次，时钟拨到它开放之后', async () => {
      const t = await now(anon);
      const d = new Date(t);
      let ws = Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate(), 13);
      if (t > ws - 3 * HOUR + 60_000) ws += 24 * HOUR;
      await advanceTo(anon, ws - 3 * HOUR + 60_000);
      return ws;
    });
    const admin = await step('超管（拨过时钟，重新登录）启用默认模板：BTC 1 小时、按场次 13:00 UTC；勾选"已按清单检查"', async () => {
      const a = await adminSession(anon);
      const list = await json<Autogen[]>(await a.get('/api/admin/market-autogens'), '自动生成模板');
      const btc = list.find((x) => x.symbol === 'BTCUSDT' && x.period === '1H');
      expect(btc, '默认模板').toBeTruthy();
      expect(btc!.mode).toBe('schedule');
      autogenNo = btc!.autogenNo;
      const bad = await a.post(`/api/admin/market-autogens/${autogenNo}/enable`, { data: {} });
      expect(bad.status(), '不勾选不能启用').toBe(400);
      const on = await json<Autogen>(await a.post(`/api/admin/market-autogens/${autogenNo}/enable`, { data: { checked: true } }), '启用');
      expect(on.enabled).toBe(true);
      return a;
    });
    const marketNo = await step('生成任务跑一轮：这一场生成并直接上架', async () => {
      await json(await anon.post('/api/e2e/autogen/tick'), '生成');
      const d = await json<Autogen>(await admin.get(`/api/admin/market-autogens/${autogenNo}`), '模板详情');
      const w = d.upcoming.find((x) => new Date(x.windowStart).getTime() === window);
      expect(w?.generated, '这一场已生成').toBe(true);
      return w!.marketNo!;
    });
    const up = await step('注册客户 A（押涨）', () => clientSession(anon, 'up'), 90_000);
    const down = await step('注册客户 B（押跌）', () => clientSession(anon, 'down'), 90_000);
    const opts = await step('两人各充值 200；客户端看到这一场（已上架、可下注）', async () => {
      await fund(anon, up.api, '200');
      await fund(anon, down.api, '200');
      const m = await json<{ status: string; category: string; title: { zhCN: string }; closeAt: string; options: { optionNo: string; seq: number }[] }>(
        await up.api.get(`/api/markets/${marketNo}`),
        '市场详情',
      );
      expect(m.status).toBe('listed');
      expect(m.category).toBe('CRYPTO_1H');
      expect(m.title.zhCN).toContain('BTC 涨还是跌');
      expect(new Date(m.closeAt).getTime(), '窗口开始前 30 秒截止').toBe(window - 30_000);
      return m.options;
    });
    await step('A 押涨 60、B 押跌 40', async () => {
      for (const [c, seq, amount] of [[up, 1, '60'], [down, 2, '40']] as const) {
        const optionNo = opts.find((o) => o.seq === seq)!.optionNo;
        await json(await c.api.post(`/api/markets/${marketNo}/bets`, { data: { optionNo, amount, clientReqId: randomUUID() } }), `下注 ${amount}`);
      }
    });
    await step('替身数据源：币安、OKX 这个窗口开始价 = 结束价 = 67000（E9 判涨）；每次回复慢 1.5 秒（让并发的取数一定重叠）', async () => {
      await json(
        await anon.post('/api/e2e/candles', {
          data: {
            symbol: 'BTCUSDT',
            windowStart: new Date(window).toISOString(),
            windowEnd: new Date(window + HOUR).toISOString(),
            open: '67000',
            close: '67000',
            delayMs: 1500,
          },
        }),
        '设 K 线',
      );
    });
    await step('窗口开始前 30 秒截止；窗口结束进入待裁决', async () => {
      await advanceTo(anon, window - 30_000);
      await json(await anon.post('/api/e2e/markets/tick'), '截止任务');
      await advanceTo(anon, window + HOUR);
      await json(await anon.post('/api/e2e/markets/tick'), '事件结束任务');
      expect((await json<{ status: string }>(await up.api.get(`/api/markets/${marketNo}`), '市场详情')).status).toBe('pending_resolution');
    });
    await step('窗口结束 + 1 分钟：同时两个触发（模拟定时任务和手动触发）→ 只有一个取数、自动裁决 → 自动结算（3 分钟内，AC-27）', async () => {
      await advanceTo(anon, window + HOUR + 60_000);
      const ticks = await Promise.all([1, 2].map(() => anon.post('/api/e2e/resolve/tick').then((r) => json<{ markets: Record<string, string> }>(r, '自动裁决'))));
      // 修复前同时到的两个都会取数，后到的条件更新失败返回 raced；修复后没认领到的返回 busy（或这一轮根本没看到它）
      for (const t of ticks) for (const [id, v] of Object.entries(t.markets)) expect(v, `市场 ${id} 这一轮的结果`).not.toMatch(/^(raced|error)$/);
      await json(await anon.post('/api/e2e/settle/tick'), '派彩（分批模式）');
      await expect
        .poll(async () => (await json<{ status: string }>(await up.api.get(`/api/markets/${marketNo}`), '市场详情')).status, { message: '等已结算', timeout: 20_000 })
        .toBe('settled');
      expect((await now(anon)) - (window + HOUR)).toBeLessThanOrEqual(3 * 60_000);
    });
    await step('余额：T = 100、赢方（涨）60，A 派彩 60 + 40 − 2 = 98；B 只扣本金', async () => {
      const a = await json<Account>(await up.api.get('/api/ledger/account'), 'A 账户');
      expect(a.available).toBe('238');
      expect(a.betLocked).toBe('0');
      const b = await json<Account>(await down.api.get('/api/ledger/account'), 'B 账户');
      expect(b.available).toBe('160');
    });
    await step('详情公开开始价、结束价、来源；后台看得到两个来源的原始返回（sha256）', async () => {
      const d = await json<{ winningOptionNo: string; resolution: { result: string; startPrice: string; endPrice: string; source: string; resolvedBy: string; sources: { source: string; sha256: string }[] } }>(
        await up.api.get(`/api/markets/${marketNo}`),
        '市场详情',
      );
      expect(d.winningOptionNo).toBe(opts.find((o) => o.seq === 1)!.optionNo);
      expect(d.resolution.result).toBe('up');
      expect(d.resolution.resolvedBy).toBe('AUTO');
      expect(d.resolution.startPrice).toBe('67000');
      expect(d.resolution.endPrice).toBe('67000');
      expect(d.resolution.source).toBe('Binance BTC/USDT');
      expect(d.resolution.sources.map((s) => s.source)).toEqual(['BINANCE', 'OKX']);
      const a = await adminSession(anon); // 拨过时钟，后台会话过期，重新登录
      const ev = await json<{ task: { status: string }; fetches: { source: string; outcome: string; bodySha256: string; httpStatus: number }[] }>(
        await a.get(`/api/admin/markets/${marketNo}/resolution`),
        '裁决依据',
      );
      expect(ev.task.status).toBe('resolved');
      const ok = ev.fetches.filter((f) => f.outcome === 'ok');
      expect(ok.map((f) => f.source).sort()).toEqual(['BINANCE', 'OKX']);
      expect(ev.fetches, '原始返回只有一组（两个来源各一行，没有并发多取的）').toHaveLength(2);
      for (const f of ok) {
        expect(f.httpStatus).toBe(200);
        expect(f.bodySha256).toMatch(/^[0-9a-f]{64}$/);
      }
      await a.dispose();
    });
    await up.api.dispose();
    await down.api.dispose();
    await admin.dispose();
  } finally {
    // 关掉模板（不影响后面的用例），时钟复原
    if (autogenNo) {
      const a = await adminSession(anon).catch(() => null);
      if (a) {
        await a.post(`/api/admin/market-autogens/${autogenNo}/disable`, { data: { reason: '端到端结束' } });
        await a.dispose();
      }
    }
    await anon.post('/api/e2e/clock/reset');
    await anon.dispose();
  }
});
