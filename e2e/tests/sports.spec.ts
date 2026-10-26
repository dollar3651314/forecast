/**
 * 体育赛程 + 自动裁决的端到端（forecast S8，技术方案/v1-架构方案.md 第 16 节 S8；需求说明书 v1.3 第 4.2、4.3 节）：接口级，页面在原型确认后做（S9、S11）。
 * 后端 e2e profile 下 football-data、balldontlie、TheSportsDB 的地址指向 /api/e2e/sports/**（resolve/web/E2eSports），走真实的适配器、网关、存档、
 * 赛程同步、候选、生成草稿和体育裁决器。两种形态都跑；结算事务边界跟后端配置（默认 500，分批时用 /api/e2e/settle/tick 立即派完）。
 * 1. 足球：赛程同步 → 找候选 → 确认对应 → 一键生成草稿（大小球 2.5）→ 提交上架 → 上架 → 下注 → 截止 → 完赛 → 等 30 分钟 → 两个来源一致 → 自动结算；
 * 2. NBA：两个来源比分不一致 → 转人工（裁决台"自动裁决失败"）。
 */
import { expect, request, test, type APIRequestContext } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { STUB_URL } from './targets';
import { step } from './support';
import { adminSession, clientSession, fund, json } from './api';

type Fixture = { fixtureNo: string; primaryRef: string; candidates: { ref: string; suggested: boolean; score: number }[]; mapping: { ref: string } | null };
type Account = { available: string; betLocked: string };
type Market = { status: string; category: string; title: { zhCN: string }; closeAt: string; options: { optionNo: string; seq: number }[] };
const MIN = 60_000;

async function now(anon: APIRequestContext): Promise<number> {
  return new Date((await json<{ now: string }>(await anon.get('/api/e2e/clock'), '读时钟')).now).getTime();
}

async function advanceTo(anon: APIRequestContext, target: number) {
  const ms = target - (await now(anon));
  if (ms > 0) await json(await anon.post('/api/e2e/clock/advance', { data: { millis: ms } }), `拨时钟 ${new Date(target).toISOString()}`);
}

/** 一个不重复的数字编号（两个来源各用一个） */
function refs(): [string, string] {
  const base = (Date.now() % 1_000_000_000) * 10 + Math.floor(Math.random() * 10);
  return [String(base), String(base + 5)];
}

/** 替身里加一场比赛 → 后台同步 → 找候选 → 确认推荐的 → 生成草稿 → 提交上架 → 上架；返回市场编号 */
async function listFromFixture(
  anon: APIRequestContext,
  admin: APIRequestContext,
  sport: 'FOOTBALL' | 'NBA',
  teams: { home: string; away: string; backupHome: string; backupAway: string },
  kickoff: number,
  play: string,
): Promise<{ marketNo: string; primaryRef: string }> {
  const [primaryRef, backupRef] = refs();
  await json(
    await anon.post('/api/e2e/sports/fixtures', {
      data: { sport, primaryRef, backupRef, competition: sport === 'NBA' ? 'NBA' : 'PL', competitionName: sport === 'NBA' ? 'NBA' : 'Premier League', ...teams, kickoffAt: new Date(kickoff).toISOString() },
    }),
    '替身加比赛',
  );
  const sync = await json<{ sport: string; outcome: string; fetched: number }[]>(await admin.post('/api/admin/sports/fixtures/sync', { data: { sport } }), '同步赛程');
  expect(sync[0].outcome).toBe('ok');
  const list = await json<{ items: Fixture[] }>(await admin.get(`/api/admin/sports/fixtures?sport=${sport}&pageSize=100`), '赛程列表');
  const fx = list.items.find((f) => f.primaryRef === primaryRef);
  expect(fx, '同步进来了').toBeTruthy();
  const c = await json<Fixture>(await admin.post(`/api/admin/sports/fixtures/${fx!.fixtureNo}/candidates`, { data: {} }), '找候选');
  expect(c.candidates[0].ref, '候选是备源里的同一场').toBe(backupRef);
  expect(c.candidates[0].suggested).toBe(true);
  expect(c.mapping, '只是推荐，不自动确认').toBeNull();
  const m = await json<Fixture>(await admin.post(`/api/admin/sports/fixtures/${fx!.fixtureNo}/mapping`, { data: { ref: backupRef } }), '确认对应');
  expect(m.mapping!.ref).toBe(backupRef);
  const g = await json<{ created: { marketNo: string; status: string; line: string | null }[]; warnings: string[] }>(
    await admin.post(`/api/admin/sports/fixtures/${fx!.fixtureNo}/drafts`, { data: { plays: [{ play }] } }),
    '生成草稿',
  );
  expect(g.warnings).toEqual([]);
  expect(g.created[0].status, '自动生成的不会自动上架').toBe('draft');
  const marketNo = g.created[0].marketNo;
  await json(await admin.post(`/api/admin/markets/${marketNo}/submit`), '提交上架');
  await json(await admin.post(`/api/admin/markets/${marketNo}/publish`, { data: { checked: true } }), '上架');
  return { marketNo, primaryRef };
}

test('体育：赛程生成草稿 → 上架 → 下注 → 完赛 → 两个来源一致 → 自动结算', async () => {
  const anon = await request.newContext({ baseURL: STUB_URL });
  try {
    const kickoff = await step('开赛时间：现在 + 1 天（取整到分钟）', async () => Math.floor(((await now(anon)) + 24 * 60 * MIN) / MIN) * MIN);
    const admin = await step('超管登录', () => adminSession(anon));
    const { marketNo, primaryRef } = await step('同步赛程 → 找候选 → 确认对应 → 生成大小球草稿（默认 2.5）→ 上架', () =>
      listFromFixture(anon, admin, 'FOOTBALL', { home: 'Liverpool FC', away: 'Chelsea FC', backupHome: 'Liverpool', backupAway: 'Chelsea' }, kickoff, 'FOOTBALL_OU'),
    );
    const over = await step('注册客户 A（押大）', () => clientSession(anon, 'over'), 90_000);
    const under = await step('注册客户 B（押小）', () => clientSession(anon, 'under'), 90_000);
    const opts = await step('两人各充值 200；客户端看到这场（已上架、开赛前 5 分钟截止）', async () => {
      await fund(anon, over.api, '200');
      await fund(anon, under.api, '200');
      const m = await json<Market>(await over.api.get(`/api/markets/${marketNo}`), '市场详情');
      expect(m.status).toBe('listed');
      expect(m.category).toBe('FOOTBALL');
      expect(m.title.zhCN).toBe('Liverpool FC vs Chelsea FC：总进球大于还是小于 2.5');
      expect(new Date(m.closeAt).getTime()).toBe(kickoff - 5 * MIN);
      return m.options;
    });
    await step('A 押大 60、B 押小 40', async () => {
      for (const [c, seq, amount] of [[over, 1, '60'], [under, 2, '40']] as const) {
        const optionNo = opts.find((o) => o.seq === seq)!.optionNo;
        await json(await c.api.post(`/api/markets/${marketNo}/bets`, { data: { optionNo, amount, clientReqId: randomUUID() } }), `下注 ${amount}`);
      }
    });
    await step('截止；开赛 + 2 小时进入待裁决', async () => {
      await advanceTo(anon, kickoff - 5 * MIN);
      await json(await anon.post('/api/e2e/markets/tick'), '截止任务');
      await advanceTo(anon, kickoff + 120 * MIN);
      await json(await anon.post('/api/e2e/markets/tick'), '事件结束任务');
      expect((await json<{ status: string }>(await over.api.get(`/api/markets/${marketNo}`), '市场详情')).status).toBe('pending_resolution');
    });
    await step('两个来源都报告完赛 2:1：第一次看到完赛，开始等 30 分钟（不结算）', async () => {
      await json(await anon.post('/api/e2e/sports/state', { data: { primaryRef, status: 'FINISHED', homeScore: 2, awayScore: 1 } }), '设比分');
      await json(await anon.post('/api/e2e/sports/tick'), '体育自动裁决');
      expect((await json<{ status: string }>(await over.api.get(`/api/markets/${marketNo}`), '市场详情')).status).toBe('pending_resolution');
    });
    await step('完赛 30 分钟后：两个来源一致 → 自动结算', async () => {
      await advanceTo(anon, (await now(anon)) + 30 * MIN);
      await json(await anon.post('/api/e2e/sports/tick'), '体育自动裁决');
      await json(await anon.post('/api/e2e/settle/tick'), '派彩（分批模式）');
      await expect
        .poll(async () => (await json<{ status: string }>(await over.api.get(`/api/markets/${marketNo}`), '市场详情')).status, { message: '等已结算', timeout: 20_000 })
        .toBe('settled');
    });
    await step('余额：T = 100、赢方（大）60，A 派彩 60 + 40 − 2 = 98；B 只扣本金', async () => {
      const a = await json<Account>(await over.api.get('/api/ledger/account'), 'A 账户');
      expect(a.available).toBe('238');
      expect(a.betLocked).toBe('0');
      const b = await json<Account>(await under.api.get('/api/ledger/account'), 'B 账户');
      expect(b.available).toBe('160');
    });
    await step('详情公开结果、比分、来源；后台裁决依据有两个来源的原始返回（sha256，地址里没有 key）', async () => {
      const d = await json<{ resolution: { result: string; homeScore: number; awayScore: number; source: string; resolvedBy: string; sources: { source: string; sha256: string }[] } }>(
        await over.api.get(`/api/markets/${marketNo}`),
        '市场详情',
      );
      expect(d.resolution.result).toBe('over');
      expect(d.resolution.homeScore).toBe(2);
      expect(d.resolution.awayScore).toBe(1);
      expect(d.resolution.resolvedBy).toBe('AUTO');
      expect(d.resolution.source).toBe('football-data.org');
      expect(d.resolution.sources.map((s) => s.source)).toEqual(['FOOTBALL_DATA', 'THESPORTSDB']);
      const a = await adminSession(anon); // 拨过时钟，后台会话过期，重新登录
      const ev = await json<{ sports: { status: string; backupRef: string }; fetches: { source: string; outcome: string; bodySha256: string; requestUrl: string }[] }>(
        await a.get(`/api/admin/markets/${marketNo}/resolution`),
        '裁决依据',
      );
      expect(ev.sports.status).toBe('resolved');
      const ok = ev.fetches.filter((f) => f.outcome === 'ok');
      expect([...new Set(ok.map((f) => f.source))].sort()).toEqual(['FOOTBALL_DATA', 'THESPORTSDB']);
      for (const f of ok) expect(f.bodySha256).toMatch(/^[0-9a-f]{64}$/);
      for (const f of ok.filter((x) => x.source === 'THESPORTSDB')) expect(f.requestUrl).toContain('/json/{key}/');
      await a.dispose();
    });
    await over.api.dispose();
    await under.api.dispose();
    await admin.dispose();
  } finally {
    await anon.post('/api/e2e/clock/reset');
    await anon.dispose();
  }
});

test('体育：两个来源比分不一致 → 转人工（裁决台"自动裁决失败"）', async () => {
  const anon = await request.newContext({ baseURL: STUB_URL });
  try {
    const kickoff = Math.floor(((await now(anon)) + 24 * 60 * MIN) / MIN) * MIN;
    const admin = await step('超管登录', () => adminSession(anon));
    const { marketNo, primaryRef } = await step('NBA：同步 → 确认对应 → 生成胜负草稿 → 上架', () =>
      listFromFixture(anon, admin, 'NBA', { home: 'Los Angeles Lakers', away: 'Boston Celtics', backupHome: 'Los Angeles Lakers', backupAway: 'Boston Celtics' }, kickoff, 'NBA_ML'),
    );
    const home = await step('注册客户 A（押主队）', () => clientSession(anon, 'home'), 90_000);
    const away = await step('注册客户 B（押客队）', () => clientSession(anon, 'away'), 90_000);
    await step('各充值 100、各押 10', async () => {
      await fund(anon, home.api, '100');
      await fund(anon, away.api, '100');
      const m = await json<Market>(await home.api.get(`/api/markets/${marketNo}`), '市场详情');
      for (const [c, seq] of [[home, 1], [away, 2]] as const) {
        const optionNo = m.options.find((o) => o.seq === seq)!.optionNo;
        await json(await c.api.post(`/api/markets/${marketNo}/bets`, { data: { optionNo, amount: '10', clientReqId: randomUUID() } }), '下注');
      }
    });
    await step('截止；开赛 + 3 小时进入待裁决', async () => {
      await advanceTo(anon, kickoff - 5 * MIN);
      await json(await anon.post('/api/e2e/markets/tick'), '截止任务');
      await advanceTo(anon, kickoff + 180 * MIN);
      await json(await anon.post('/api/e2e/markets/tick'), '事件结束任务');
    });
    await step('主源 110:104、备源 110:106 → 等 30 分钟后比较：不一致 → 转人工，市场留在待裁决', async () => {
      await json(await anon.post('/api/e2e/sports/state', { data: { primaryRef, source: 'PRIMARY', status: 'FINISHED', homeScore: 110, awayScore: 104 } }), '主源比分');
      await json(await anon.post('/api/e2e/sports/state', { data: { primaryRef, source: 'BACKUP', status: 'FINISHED', homeScore: 110, awayScore: 106 } }), '备源比分');
      await json(await anon.post('/api/e2e/sports/tick'), '体育自动裁决');
      await advanceTo(anon, (await now(anon)) + 30 * MIN);
      await json(await anon.post('/api/e2e/sports/tick'), '体育自动裁决');
      expect((await json<{ status: string }>(await home.api.get(`/api/markets/${marketNo}`), '市场详情')).status).toBe('pending_resolution');
    });
    await step('裁决台"自动裁决失败"里有这个市场；裁决依据写明比分不一致', async () => {
      const a = await adminSession(anon);
      const cases = await json<{ items: { marketNo: string; origin: string }[] }>(await a.get('/api/admin/resolve/cases?tab=auto_failed&pageSize=100'), '裁决台');
      expect(cases.items.find((c) => c.marketNo === marketNo)?.origin).toBe('auto_failed');
      const ev = await json<{ sports: { status: string; lastReason: string }; records: { kind: string }[] }>(
        await a.get(`/api/admin/markets/${marketNo}/resolution`),
        '裁决依据',
      );
      expect(ev.sports.status).toBe('manual');
      expect(ev.sports.lastReason).toContain('比分不一致');
      expect(ev.records.map((r) => r.kind)).toEqual(['auto_failed']);
      await a.dispose();
    });
    await home.api.dispose();
    await away.api.dispose();
    await admin.dispose();
  } finally {
    await anon.post('/api/e2e/clock/reset');
    await anon.dispose();
  }
});
