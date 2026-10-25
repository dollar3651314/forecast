/**
 * 平台裁决的争议改判端到端（forecast S7；技术方案/v1-架构方案.md 第 17 节"平台裁决 → 公示 → 争议 → 改判 → 补偿"；AC-30～AC-35）：接口级，页面在原型确认后做（S9、S11）。
 * 截图上传 → 提议（带截图、结算预览）→ 确认（公示，争议窗口 2 分钟）→ 输的一方提交争议（每人一次）→ 改判 → 直接结算（不再公示）→ 余额逐分 →
 * 详情公开结论、不显示争议人 → 争议人收到 #15、所有下注人收到 #16 → 发现还是错了：补偿（只补不扣，平台补偿支出单列）→ 补偿到账 #17。
 * 两种形态都跑；结算的事务边界跟后端配置（默认 500，分批时用 /api/e2e/settle/tick 立即派完）。
 */
import { expect, request, test, type APIRequestContext } from '@playwright/test';
import { STUB_URL } from './targets';
import { PNG_1PX, step } from './support';
import { adminSession, bet, clientSession, fund, json, listMarket } from './api';

type Account = { available: string; betLocked: string };
type Resolution = {
  mode: string;
  resultOptionNo: string | null;
  publicizedOptionNo: string | null;
  resolvedBy: string;
  disputeDeadlineAt: string;
  disputeCount: number;
  dispute: { decision: string; conclusion: string } | null;
  myDispute: { claimedOptionNo: string; status: string } | null;
  canDispute: boolean;
};
type Detail = { status: string; resolution: Resolution };

const account = async (c: APIRequestContext) => json<Account>(await c.get('/api/ledger/account'), '账户');

async function titles(c: APIRequestContext): Promise<string> {
  const r = await json<{ items: { title: string }[] }>(await c.get('/api/notifications'), '站内信');
  return r.items.map((i) => i.title).join('\n');
}

test('平台裁决：提议 → 确认 → 公示 → 争议 → 改判 → 直接结算 → 公开结论 → 补偿', async () => {
  const anon = await request.newContext({ baseURL: STUB_URL });
  try {
    let admin = await step('超管登录', () => adminSession(anon));
    const a = await step('注册客户 A（押"是"）', () => clientSession(anon, 'rsa'), 90_000);
    const b = await step('注册客户 B（押"否"）', () => clientSession(anon, 'rsb'), 90_000);
    await step('两人各充值 200', async () => {
      await fund(anon, a.api, '200');
      await fund(anon, b.api, '200');
    });
    const m = await step('建平台裁决的市场并上架（争议窗口 2 分钟）', () => listMarket(anon, admin, '端到端争议市场'));
    await step('A 押"是" 100、B 押"否" 50；截止 → 事件结束 → 待裁决', async () => {
      await bet(a.api, m.no, m.yes, '100');
      await bet(b.api, m.no, m.nope, '50');
      await json(await anon.post('/api/e2e/clock/advance', { data: { millis: 120_000 } }), '拨到截止');
      await json(await anon.post('/api/e2e/markets/tick'), '截止任务');
      await json(await anon.post('/api/e2e/clock/advance', { data: { millis: 60_000 } }), '拨到事件结束');
      await json(await anon.post('/api/e2e/markets/tick'), '事件结束任务');
      expect((await json<Detail>(await a.api.get(`/api/markets/${m.no}`), '详情')).status).toBe('pending_resolution');
    });
    await step('上传存档截图 → 提议"是"（结算预览守恒）→ 确认 → 公示中', async () => {
      admin = await adminSession(anon);
      const att = await json<{ attachmentNo: string; sha256: string }>(
        await admin.post(`/api/admin/resolve/cases/${m.no}/attachments`, { multipart: { file: { name: 'shot.png', mimeType: 'image/png', buffer: PNG_1PX } } }),
        '上传截图',
      );
      expect(att.sha256).toHaveLength(64);
      const p = await json<{ detail: { summary: { proposal: { proposalId: string } } }; proposalPreview: { sumPayout: string; platformIncome: string; conserved: boolean } }>(
        await admin.post(`/api/admin/resolve/cases/${m.no}/proposals`, {
          data: { optionNo: m.yes, evidenceUrls: ['https://example.org/official'], attachmentNos: [att.attachmentNo], note: '官方公布：是' },
        }),
        '提议',
      );
      // T = 150，"是"赢：G = 100 × 50 ÷ 100 = 50，抽成 2.50，派彩 147.50
      expect(p.proposalPreview.sumPayout).toBe('147.50');
      expect(p.proposalPreview.platformIncome).toBe('2.50');
      expect(p.proposalPreview.conserved).toBe(true);
      await json(
        await admin.post(`/api/admin/resolve/cases/${m.no}/proposals/${p.detail.summary.proposal.proposalId}/confirm`, { data: { checked: true, reason: '核对官网' } }),
        '确认',
      );
      expect((await json<Detail>(await b.api.get(`/api/markets/${m.no}`), '详情')).status).toBe('publicizing');
    });
    await step('B 提交争议（每人一次；理由必填）→ 争议中；到期也不会自动结算', async () => {
      const empty = await b.api.post(`/api/markets/${m.no}/disputes`, { data: { claimedOptionNo: m.nope, reason: '' } });
      expect(empty.status(), '理由必填').toBe(400);
      const r = await json<{ disputeNo: string; status: string }>(
        await b.api.post(`/api/markets/${m.no}/disputes`, { data: { claimedOptionNo: m.nope, reason: '官网更正为"否"', link: 'https://example.org/correction' } }),
        '提交争议',
      );
      expect(r.status).toBe('open');
      const again = await b.api.post(`/api/markets/${m.no}/disputes`, { data: { claimedOptionNo: 'void', reason: '再来一次' } });
      expect(again.status(), '每人每市场一次').toBe(409);
      const d = await json<Detail>(await b.api.get(`/api/markets/${m.no}`), '详情');
      expect(d.status).toBe('disputed');
      expect(d.resolution.myDispute?.claimedOptionNo).toBe(m.nope);
      expect(d.resolution.canDispute).toBe(false);
      await json(await anon.post('/api/e2e/clock/advance', { data: { millis: 150_000 } }), '拨过争议截止');
      await json(await anon.post('/api/e2e/resolve/tick'), '公示任务');
      expect((await json<Detail>(await a.api.get(`/api/markets/${m.no}`), '详情')).status, '争议中不自动结算').toBe('disputed');
    });
    await step('后台看争议（有争议人编号）→ 改判为"否"（高危）→ 直接结算', async () => {
      admin = await adminSession(anon);
      const list = await json<{ items: { marketNo: string; status: string }[] }>(await admin.get('/api/admin/resolve/disputes?status=open'), '争议列表');
      expect(list.items.some((x) => x.marketNo === m.no)).toBe(true);
      const bad = await admin.post(`/api/admin/resolve/cases/${m.no}/disputes/decide`, { data: { decision: 'overturn', optionNo: m.nope, note: '核对后改判' } });
      expect(bad.status(), '没有原因、没有勾选：400').toBe(400);
      await json(
        await admin.post(`/api/admin/resolve/cases/${m.no}/disputes/decide`, {
          data: { decision: 'overturn', optionNo: m.nope, note: '核对官网更正，改判为"否"', checked: true, reason: '争议成立' },
        }),
        '改判',
      );
      const o = await json<{ settlePhase: string }>(await admin.get(`/api/admin/markets/${m.no}/settlement`), '结算情况');
      if (o.settlePhase === 'paying') await json(await anon.post('/api/e2e/settle/tick'), '派彩（分批模式）');
      // T = 150，"否"赢：G = 50 × 100 ÷ 50 = 100，抽成 5，派彩 145
      await expect.poll(async () => (await account(b.api)).available, { message: '等派彩', timeout: 10_000 }).toBe('295');
      expect((await account(a.api)).available).toBe('100');
    });
    await step('详情公开结论、裁决人编号（处理争议的人），不显示争议人；#15、#16、结算通知', async () => {
      const d = await json<Detail>(await a.api.get(`/api/markets/${m.no}`), '详情');
      expect(d.status).toBe('settled');
      expect(d.resolution.mode).toBe('platform');
      expect(d.resolution.resultOptionNo).toBe(m.nope);
      expect(d.resolution.publicizedOptionNo).toBe(m.yes);
      expect(d.resolution.dispute?.decision).toBe('overturned');
      expect(d.resolution.dispute?.conclusion).toBe('核对官网更正，改判为"否"');
      expect(d.resolution.resolvedBy).toMatch(/^\d+$/);
      expect(JSON.stringify(d.resolution)).not.toContain(b.email);
      expect(JSON.stringify(d.resolution)).not.toContain('官网更正为');
      expect(await titles(b.api)).toContain('争议有结论');
      expect(await titles(a.api)).toContain('改判');
      expect(await titles(b.api)).toContain('你赢了');
    });
    await step('发现"是"才对：补偿预览 → 确认（高危）→ A 补 147.50，B 不扣；报表补偿单列', async () => {
      const plan = await json<{ total: string; lines: { amount: string }[] }>(
        await admin.get(`/api/admin/markets/${m.no}/compensation-preview?correctOptionNo=${m.yes}`),
        '补偿预览',
      );
      expect(plan.total).toBe('147.50');
      expect(plan.lines).toHaveLength(1);
      const bad = await admin.post(`/api/admin/markets/${m.no}/compensations`, { data: { correctOptionNo: m.yes } });
      expect(bad.status()).toBe(400);
      const c = await json<{ compensationNo: string; total: string }>(
        await admin.post(`/api/admin/markets/${m.no}/compensations`, { data: { correctOptionNo: m.yes, checked: true, reason: '再次核对官网' } }),
        '补偿',
      );
      expect(c.total).toBe('147.50');
      expect((await account(a.api)).available).toBe('247.5');
      expect((await account(b.api)).available, '只补不扣').toBe('295');
      expect(await titles(a.api)).toContain('补偿');
      const flow = await json<{ items: { type: string; amount: string }[] }>(await a.api.get('/api/ledger/entries?range=all'), '资金流水');
      expect(flow.items.map((i) => `${i.type} ${i.amount}`)).toContain('compensation 147.5');
      const now = (await json<{ now: string }>(await anon.get('/api/e2e/clock'), '读时钟')).now.slice(0, 10);
      const rep = await json<{ compensationExpense: string; revenue: string }>(await admin.get(`/api/admin/reports/settle?from=${now}&to=${now}`), '报表');
      expect(Number(rep.compensationExpense)).toBeGreaterThanOrEqual(147.5);
    });
    await admin.dispose();
    await a.api.dispose();
    await b.api.dispose();
  } finally {
    await anon.post('/api/e2e/clock/reset');
    await anon.dispose();
  }
});
