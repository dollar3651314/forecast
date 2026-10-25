/**
 * 主线端到端（forecast S5，技术方案/v1-架构方案.md 第 16 节"主线 充值 → 押注 → 结算 → 提现在 S5 打通"）：接口级，页面在原型确认后做（S11）。
 * 注册 → 替身链充值 → 下注（两边各有人）→ 截止 → 超管填结果（S7 正式裁决台之前的临时入口）→ 结算 → 赢家余额逐分正确、
 * 流水有派彩和抽成、站内信 #11 / #12 → KYC → 白名单 → 提现 → 后台审核 → 链上确认 → 赢家余额扣掉提现。
 * 另一个用例：截止前取消，下注全额退回、通知 #13。
 * 两种形态都跑；结算的事务边界跟后端配置（FC_SETTLE_BATCH_SIZE，默认 500）：分批时用 /api/e2e/settle/tick 立即派完。
 */
import { expect, request, test, type APIRequestContext } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { STUB_URL } from './targets';
import { PNG_1PX, e2e, step } from './support';
import { adminSession, clientSession, fund, json, t } from './api';

type Account = { balance: string; available: string; betLocked: string; withdrawFrozen: string };
type Outcome = { marketNo: string; marketStatus: string; settlePhase: string; payout: { sumPayout: string; sumFee: string; dust: string; runNo: string } | null };

const account = async (c: APIRequestContext) => json<Account>(await c.get('/api/ledger/account'), '账户');

/** 后台建一个两个选项的市场并上架：开放 = 现在 − 1 分钟，截止 = 现在 + 2 分钟，事件结束 = 现在 + 3 分钟（可调时钟） */
async function listMarket(anon: APIRequestContext, admin: APIRequestContext, title: string) {
  const now = new Date((await json<{ now: string }>(await anon.get('/api/e2e/clock'), '读时钟')).now);
  const at = (ms: number) => new Date(now.getTime() + ms).toISOString();
  const draft = await json<{ publicNo: string; options: { optionNo: string }[] }>(
    await admin.post('/api/admin/markets', {
      data: {
        category: 'MACRO',
        title: t(title),
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
  await json(await admin.post(`/api/admin/markets/${draft.publicNo}/publish`, { data: { checked: true } }), '上架');
  return { no: draft.publicNo, yes: draft.options[0].optionNo, nope: draft.options[1].optionNo };
}

async function bet(c: APIRequestContext, marketNo: string, optionNo: string, amount: string) {
  return json<{ betNo: string }>(await c.post(`/api/markets/${marketNo}/bets`, { data: { optionNo, amount, clientReqId: randomUUID() } }), `下注 ${amount}`);
}

test('主线：注册 → 充值 → 两边下注 → 截止 → 超管填结果 → 结算 → 赢家余额正确 → 提现完成', async () => {
  const anon = await request.newContext({ baseURL: STUB_URL });
  try {
    let admin = await step('超管登录；冷静期、白名单生效时间设成 0（规则本身由后端测试覆盖）', async () => {
      const a = await adminSession(anon);
      for (const key of ['withdraw.coolingHours', 'whitelist.effectiveHours']) {
        await json(await a.put(`/api/admin/configs/${key}`, { data: { value: '0', reason: '端到端测试' } }), `改 ${key}`);
      }
      return a;
    });
    const winner = await step('注册客户 A（押"是"）', () => clientSession(anon, 'win'), 90_000);
    const loser = await step('注册客户 B（押"否"）', () => clientSession(anon, 'lose'), 90_000);
    await step('两人各充值 200 USDT（链替身，三条链里用 TRC20）', async () => {
      await fund(anon, winner.api, '200');
      await fund(anon, loser.api, '200');
    });
    const m = await step('后台建市场并上架', () => listMarket(anon, admin, '端到端主线市场'));
    await step('A 押"是" 60 + 40（两笔合成一个持仓）、B 押"否" 100', async () => {
      await bet(winner.api, m.no, m.yes, '60');
      await bet(winner.api, m.no, m.yes, '40');
      await bet(loser.api, m.no, m.nope, '100');
      expect((await account(winner.api)).betLocked).toBe('100');
    });
    await step('时钟拨到截止、再拨到事件结束：市场进入待裁决', async () => {
      await json(await anon.post('/api/e2e/clock/advance', { data: { millis: 120_000 } }), '拨到截止');
      await json(await anon.post('/api/e2e/markets/tick'), '截止任务');
      await json(await anon.post('/api/e2e/clock/advance', { data: { millis: 60_000 } }), '拨到事件结束');
      await json(await anon.post('/api/e2e/markets/tick'), '事件结束任务');
      const d = await json<{ status: string }>(await winner.api.get(`/api/markets/${m.no}`), '市场详情');
      expect(d.status).toBe('pending_resolution');
    });
    await step('超管填结果"是"并直接结算（高危：原因必填、勾选确认）', async () => {
      admin = await adminSession(anon); // 拨过时钟，后台会话过期，重新登录
      const bad = await admin.post(`/api/admin/markets/${m.no}/settle`, { data: { optionNo: m.yes } });
      expect(bad.status(), '没有原因、没有勾选：400').toBe(400);
      let o = await json<Outcome>(await admin.post(`/api/admin/markets/${m.no}/settle`, { data: { optionNo: m.yes, checked: true, reason: '官方公布：是' } }), '结算');
      if (o.settlePhase === 'paying') {
        await json(await anon.post('/api/e2e/settle/tick'), '派彩（分批模式）');
        o = await json<Outcome>(await admin.get(`/api/admin/markets/${m.no}/settlement`), '结算情况');
      }
      expect(o.marketStatus).toBe('settled');
      expect(o.settlePhase).toBe('done');
      // T = 200，赢方 100：G = 100 × 100 ÷ 100 = 100，抽成 5%：F = 5，派彩 = 100 + 100 − 5 = 195；尾差 0
      expect(o.payout?.sumPayout).toBe('195.00');
      expect(o.payout?.sumFee).toBe('5.00');
      expect(o.payout?.dust).toBe('0.00');
    });
    await step('赢家余额逐分正确；输家本金进了赢方；流水有派彩、抽成（带市场）；站内信 #11 / #12', async () => {
      const a = await account(winner.api);
      expect(a.available).toBe('295'); // 200 − 100 + 195（账户接口的金额去掉末尾的 0）
      expect(a.betLocked).toBe('0');
      const b = await account(loser.api);
      expect(b.available).toBe('100');
      expect(b.betLocked).toBe('0');
      const flow = await json<{ items: { type: string; amount: string; market: { publicNo: string } | null }[] }>(
        await winner.api.get('/api/ledger/entries?range=all'),
        '资金流水',
      );
      const lines = flow.items.map((i) => `${i.type} ${i.amount} ${i.market?.publicNo ?? '-'}`);
      expect(lines).toContain(`payout 200 ${m.no}`);
      expect(lines).toContain(`fee -5 ${m.no}`);
      const inboxA = await json<{ items: { title: string }[] }>(await winner.api.get('/api/notifications'), '站内信');
      expect(inboxA.items.map((i) => i.title).join('\n')).toContain('你赢了');
      const inboxB = await json<{ items: { title: string }[] }>(await loser.api.get('/api/notifications'), '站内信');
      expect(inboxB.items.map((i) => i.title).join('\n')).toContain('已结算');
      const d = await json<{ status: string; winningOptionNo: string }>(await winner.api.get(`/api/markets/${m.no}`), '市场详情');
      expect(d.status).toBe('settled');
      expect(d.winningOptionNo).toBe(m.yes);
    });
    await step('A 提交 KYC，后台通过', async () => {
      const r = await winner.api.post('/api/me/kyc', {
        multipart: {
          docType: 'PASSPORT',
          docNumber: `E2E${Date.now()}`.slice(0, 12),
          front: { name: 'front.png', mimeType: 'image/png', buffer: PNG_1PX },
          back: { name: 'back.png', mimeType: 'image/png', buffer: PNG_1PX },
          selfie: { name: 'selfie.png', mimeType: 'image/png', buffer: PNG_1PX },
        },
      });
      await json(r, '提交 KYC');
      const list = await json<{ items: { id: string; email: string }[] }>(
        await admin.get(`/api/admin/kyc?status=PENDING&keyword=${encodeURIComponent(winner.email)}`),
        'KYC 列表',
      );
      const app = list.items.find((k) => k.email === winner.email);
      expect(app, '待审核的 KYC').toBeTruthy();
      await json(await admin.post(`/api/admin/kyc/${app!.id}/review`, { data: { approve: true } }), 'KYC 通过');
    });
    await step('A 加白名单、提现 100 → 后台审核通过 → 链上确认：提现完成，余额扣掉', async () => {
      const wl = await json<{ id: string }>(
        await winner.api.post('/api/wallet/whitelist', { data: { network: 'ERC20', address: '0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed', label: '端到端' } }),
        '加白名单',
      );
      const w = await json<{ id: string }>(await winner.api.post('/api/wallet/withdrawals', { data: { whitelistId: wl.id, amount: '100' } }), '提现');
      await e2e.advanceWithdrawals(anon);
      await json(await admin.post(`/api/admin/withdrawals/${w.id}/approve`), '审核通过');
      await e2e.confirmWithdrawal(anon, w.id);
      await expect
        .poll(async () => (await json<{ items: { id: string; status: string }[] }>(await winner.api.get('/api/wallet/withdrawals'), '提现记录')).items.find((x) => x.id === w.id)?.status, {
          message: '等提现完成',
          timeout: 20_000,
        })
        .toBe('completed');
      const a = await account(winner.api);
      expect(a.available).toBe('195');
      expect(a.withdrawFrozen).toBe('0');
    });
    await admin.dispose();
    await winner.api.dispose();
    await loser.api.dispose();
  } finally {
    await anon.post('/api/e2e/clock/reset');
    await anon.dispose();
  }
});

test('截止前取消：下注全额退回、不收费、通知 #13', async () => {
  const anon = await request.newContext({ baseURL: STUB_URL });
  try {
    const admin = await step('超管登录', () => adminSession(anon));
    const c = await step('注册客户', () => clientSession(anon, 'refund'), 90_000);
    await step('充值 50', () => fund(anon, c.api, '50'));
    const m = await step('建市场并上架', () => listMarket(anon, admin, '端到端取消市场'));
    await step('押两个选项 10 + 15.5', async () => {
      await bet(c.api, m.no, m.yes, '10');
      await bet(c.api, m.no, m.nope, '15.5');
      expect((await account(c.api)).available).toBe('24.5');
    });
    await step('后台取消（原因必填）：全额退回', async () => {
      await json(await admin.post(`/api/admin/markets/${m.no}/cancel`, { data: { reason: '比赛取消（端到端）' } }), '取消');
      const o = await json<{ refund: { status: string } | null }>(await admin.get(`/api/admin/markets/${m.no}/settlement`), '结算情况');
      if (o.refund?.status === 'paying') await json(await anon.post('/api/e2e/settle/tick'), '退款（分批模式）');
      await expect.poll(async () => (await account(c.api)).available, { message: '等退款', timeout: 10_000 }).toBe('50');
      expect((await account(c.api)).betLocked).toBe('0');
      const flow = await json<{ items: { type: string; amount: string }[] }>(await c.api.get('/api/ledger/entries?type=refund&range=all'), '退款流水');
      expect(flow.items.map((i) => i.amount).sort()).toEqual(['10', '15.5']);
      const inbox = await json<{ items: { title: string }[] }>(await c.api.get('/api/notifications'), '站内信');
      expect(inbox.items.map((i) => i.title).filter((x) => x.includes('已取消'))).toHaveLength(1);
    });
    await admin.dispose();
    await c.api.dispose();
  } finally {
    await anon.dispose();
  }
});
