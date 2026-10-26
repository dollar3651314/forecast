/**
 * 下注（forecast S4）的接口级端到端测试：真实后端（profile e2e）+ 链替身充值 + 可调时钟 + 真实 WebSocket。页面在原型确认后做（S11），这里只走接口：
 * 后台上架 → 两个客户充值 → 一个客户订阅奖池推送 → 两人下注（推送 2 秒内到，AC-17）→ 详情里的持仓、我的下注、资金流水、后台下注明细 →
 * 时钟拨到截止时间、截止任务跑一轮 → 推送"已截止、奖池定格"，之后下注被拒、奖池不变。
 * 两种形态都跑：默认形态 WebSocket 经 server.mjs 转发；部署形态直接连后端 127.0.0.1:8080（和 /api/e2e/** 一样，见 targets.ts）。
 */
import { expect, request, test, type APIRequestContext } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { STUB_URL } from './targets';
import { e2e, step } from './support';
import { adminSession, clientSession, json, t } from './api';

type Pool = {
  total: string;
  bettors: number;
  frozen: boolean;
  feeRate: string;
  options: { optionNo: string; amount: string; pct: string; pctList: string; mult: string }[];
  mine: { optionNo: string; amount: string; betCount: number; ifWin: string }[] | null;
  limits: { min: string; max: string; userCap: string; used: string; remaining: string } | null;
};
type PoolMsg = { type: string; marketNo: string; status: string; ts: string; pool: Pool };

/** 订阅一个市场的奖池推送（WS /api/ws/markets，握手带登录 Cookie）；收到的消息按顺序排队 */
class PoolFeed {
  private readonly queue: { msg: PoolMsg; at: number }[] = [];
  private constructor(private readonly ws: WebSocket) {
    ws.onmessage = (e) => this.queue.push({ msg: JSON.parse(String(e.data)) as PoolMsg, at: Date.now() });
  }

  static async open(cookie: string, marketNo: string): Promise<PoolFeed> {
    const url = STUB_URL.replace(/^http/, 'ws') + '/api/ws/markets';
    // Node 22 自带的 WebSocket（undici）可以带请求头；没有 Origin 头，同源检查放行（和服务器上的非浏览器客户端一样）
    const ws = new WebSocket(url, { headers: { Cookie: cookie } } as unknown as string[]);
    await new Promise<void>((resolve, reject) => {
      ws.onopen = () => resolve();
      ws.onerror = () => reject(new Error(`WebSocket 连不上：${url}`));
    });
    const feed = new PoolFeed(ws);
    ws.send(JSON.stringify({ op: 'sub', markets: [marketNo] }));
    return feed;
  }

  /** 等一条满足条件的推送（之前的丢掉），返回它和收到的时刻 */
  async next(what: string, ok: (m: PoolMsg) => boolean, timeoutMs: number): Promise<{ msg: PoolMsg; at: number }> {
    const end = Date.now() + timeoutMs;
    while (Date.now() < end) {
      const i = this.queue.findIndex((x) => ok(x.msg));
      if (i >= 0) {
        const hit = this.queue[i];
        this.queue.splice(0, i + 1);
        return hit;
      }
      await new Promise((r) => setTimeout(r, 20));
    }
    throw new Error(`${timeoutMs} 毫秒内没有收到推送：${what}；收到过的：${JSON.stringify(this.queue.map((x) => x.msg.pool?.total + '/' + x.msg.status))}`);
  }

  close(): void {
    this.ws.close();
  }
}

/** 用链替身给客户充值（TRC20），等到账 */
async function fund(anon: APIRequestContext, client: APIRequestContext, amount: string): Promise<void> {
  const list = await json<{ network: string; address: string }[]>(await client.get('/api/wallet/addresses'), '充值地址');
  const trc = list.find((a) => a.network === 'TRC20');
  expect(trc, 'TRC20 地址').toBeTruthy();
  await e2e.deposit(anon, 'TRC20', trc!.address, amount);
  await e2e.mine(anon, 'TRC20', 25);
  await expect
    .poll(async () => (await json<{ available: string }>(await client.get('/api/ledger/account'), '账户')).available, { message: '等充值到账', timeout: 20_000 })
    .toBe(amount);
}

test('下注：上架 → 下注 → 收到奖池推送 → 截止时奖池定格（可调时钟）', async () => {
  const anon = await request.newContext({ baseURL: STUB_URL });
  let feed: PoolFeed | undefined;
  try {
    const admin = await step('超管登录', () => adminSession(anon));
    const alice = await step('注册客户 A', () => clientSession(anon, 'bet'), 90_000);
    const bob = await step('注册客户 B', () => clientSession(anon, 'bet'), 90_000);
    await step('两个客户各充值 50 USDT（链替身）', async () => {
      await fund(anon, alice.api, '50');
      await fund(anon, bob.api, '50');
    });
    const now = await step('读可调时钟', async () => new Date((await json<{ now: string }>(await anon.get('/api/e2e/clock'), '读时钟')).now));
    const at = (ms: number) => new Date(now.getTime() + ms).toISOString();

    const market = await step('后台建草稿并上架（两个选项，2 分钟后截止）', async () => {
      const draft = await json<{ publicNo: string; options: { optionNo: string }[] }>(
        await admin.post('/api/admin/markets', {
          data: {
            category: 'MACRO',
            title: t('端到端下注市场'),
            rules: t('按官方公布为准；无法判定的作废退款'),
            resolutionMode: 'PLATFORM',
            sourcePrimary: 'example.org',
            sourceBackup: 'example.net',
            openAt: at(-60_000),
            closeAt: at(120_000),
            eventStartAt: at(120_000),
            eventEndAt: at(180_000),
            options: [
              { label: t('是'), winCondition: t('公布为是') },
              { label: t('否'), winCondition: t('公布为否') },
            ],
          },
        }),
        '建草稿',
      );
      await json(await admin.post(`/api/admin/markets/${draft.publicNo}/publish`, { data: { checked: true, overLimit: true } }), '上架');
      return { no: draft.publicNo, yes: draft.options[0].optionNo, nope: draft.options[1].optionNo };
    });

    feed = await step('客户 B 订阅奖池推送：订阅时先推一次现在的奖池', async () => {
      const f = await PoolFeed.open(bob.cookie, market.no);
      const first = await f.next('订阅时的奖池', (m) => m.type === 'pool' && m.marketNo === market.no, 10_000);
      expect(first.msg.status).toBe('listed');
      expect(first.msg.pool.total).toBe('0.00');
      expect(first.msg.pool.options[0].pct, '没人押时概率显示 —').toBe('—');
      return f;
    });
    await new Promise((r) => setTimeout(r, 1300)); // 上架时的变化可能在订阅后第一轮推出来，等它过去

    await step('客户 A 押"是" 30：下注结果、2 秒内推到新的奖池（AC-17）', async () => {
      const sent = Date.now();
      const r = await json<{ betNo: string; amount: string; available: string; betLocked: string; pool: Pool }>(
        await alice.api.post(`/api/markets/${market.no}/bets`, { data: { optionNo: market.yes, amount: '30', clientReqId: randomUUID() } }),
        '下注',
      );
      expect(r.amount).toBe('30.00');
      expect(r.available).toBe('20.00');
      expect(r.betLocked).toBe('30.00');
      expect(r.pool.total).toBe('30.00');
      const pushed = await feed!.next('A 下注后的奖池 30.00', (m) => m.pool.total === '30.00', 2_000);
      expect(pushed.at - sent, '从发出下注到收到推送（毫秒）').toBeLessThan(2_000);
    });

    await step('客户 B 押"否" 20：推送里的概率和赔率', async () => {
      await json(await bob.api.post(`/api/markets/${market.no}/bets`, { data: { optionNo: market.nope, amount: '20', clientReqId: randomUUID() } }), '下注');
      const pushed = await feed!.next('B 下注后的奖池 50.00', (m) => m.pool.total === '50.00', 2_000);
      expect(pushed.msg.pool.options.map((o) => o.pct)).toEqual(['60.0%', '40.0%']);
      // 预计赔率（费率 5%）：是 (30 + 20 × 0.95) ÷ 30 = 1.6333 → 1.63；否 (20 + 30 × 0.95) ÷ 20 = 2.425 → 2.42
      expect(pushed.msg.pool.options.map((o) => o.mult)).toEqual(['1.63', '2.42']);
    });

    await step('详情里的本人持仓和限额、我的下注、资金流水、后台下注明细', async () => {
      const d = await json<{ pool: Pool }>(await alice.api.get(`/api/markets/${market.no}`), '市场详情');
      expect(d.pool.mine).toEqual([{ optionNo: market.yes, seq: 1, amount: '30.00', betCount: 1, ifWin: '49.00' }]);
      expect(d.pool.limits?.used).toBe('30.00');
      const mine = await json<{ total: number; items: { marketNo: string; tab: string }[] }>(await alice.api.get('/api/me/bets'), '我的下注');
      expect(mine.total).toBe(1);
      expect(mine.items[0].marketNo).toBe(market.no);
      expect(mine.items[0].tab).toBe('active');
      const flow = await json<{ items: { type: string; amount: string; market: { publicNo: string } | null }[] }>(
        await alice.api.get('/api/ledger/entries?type=bet&range=all'),
        '资金流水',
      );
      expect(flow.items.map((i) => `${i.type} ${i.amount} ${i.market?.publicNo}`)).toEqual([`bet -30 ${market.no}`]);
      const bets = await json<{ activeTotal: string; bets: { total: number } }>(await admin.get(`/api/admin/markets/${market.no}/bets`), '后台下注明细');
      expect(bets.activeTotal).toBe('50.00');
      expect(bets.bets.total).toBe(2);
    });

    await step('时钟拨到截止时间、截止任务跑一轮：推送"已截止"、奖池定格；之后下注被拒', async () => {
      await new Promise((r) => setTimeout(r, 1100)); // 和上一次推送隔开 1 秒（每个市场每秒最多推一次）
      await json(await anon.post('/api/e2e/clock/advance', { data: { millis: 120_000 } }), '拨时钟');
      await json(await anon.post('/api/e2e/markets/tick'), '跑截止任务');
      const closed = await feed!.next('截止后的推送', (m) => m.status === 'closed', 5_000);
      expect(closed.msg.pool.frozen).toBe(true);
      expect(closed.msg.pool.total).toBe('50.00');
      const late = await alice.api.post(`/api/markets/${market.no}/bets`, { data: { optionNo: market.yes, amount: '1', clientReqId: randomUUID() } });
      expect(late.status()).toBe(400);
      expect(((await late.json()) as { code: string }).code).toBe('MARKET_CLOSED');
      const d = await json<{ status: string; pool: Pool }>(await alice.api.get(`/api/markets/${market.no}`), '市场详情');
      expect(d.status, '两个选项都有人押：正常截止，不作废').toBe('closed');
      expect(d.pool.frozen).toBe(true);
      expect(d.pool.total).toBe('50.00');
    });
    await admin.dispose();
    await alice.api.dispose();
    await bob.api.dispose();
  } finally {
    feed?.close();
    await anon.post('/api/e2e/clock/reset');
    await anon.dispose();
  }
});
