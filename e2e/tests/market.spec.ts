/**
 * 市场（forecast S3）的接口级端到端测试：真实后端（profile e2e）+ 可调时钟。页面在原型确认后做（S11），这里只走接口：
 * 后台建草稿 → 上架 → 客户端列表和详情看到 → 改费率（修改记录公开）→ 时钟拨到截止时间 → 截止任务跑一轮 → 没有下注（S3 还没有下注，按 E4）作废。
 * 两种形态都跑（登录、Cookie 的处理见 api.ts）。S4 起有下注，没有人下注的市场到点仍按 E4 作废；有下注的见 bet.spec.ts。
 */
import { expect, request, test } from '@playwright/test';
import { STUB_URL } from './targets';
import { step } from './support';
import { adminSession, clientSession, json, t } from './api';

type Detail = { publicNo: string; status: string; feeRate: string; voidReason: string | null; feeChanges: { oldRate: string; newRate: string; time: string }[] };

test('市场：上架 → 客户端可见 → 改费率公开 → 到点截止（可调时钟）', async () => {
  const anon = await request.newContext({ baseURL: STUB_URL });
  try {
    const admin = await step('超管登录', () => adminSession(anon));
    const client = (await step('注册客户', () => clientSession(anon), 90_000)).api;
    const now = await step('读可调时钟', async () => new Date((await json<{ now: string }>(await anon.get('/api/e2e/clock'), '读时钟')).now));
    const at = (ms: number) => new Date(now.getTime() + ms).toISOString();

    const no = await step('后台建草稿并上架', async () => {
      const draft = await json<{ publicNo: string; feeRate: string; missing: unknown[] }>(
        await admin.post('/api/admin/markets', {
          data: {
            category: 'MACRO',
            title: t('端到端测试市场'),
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
      expect(draft.feeRate, '新建按品类默认 5%').toBe('5');
      expect(draft.missing).toEqual([]);
      const listed = await json<{ status: string }>(await admin.post(`/api/admin/markets/${draft.publicNo}/publish`, { data: { checked: true, overLimit: true } }), '上架');
      expect(listed.status).toBe('listed');
      return draft.publicNo;
    });

    await step('客户端列表和详情看到它', async () => {
      const page = await json<{ items: { publicNo: string; status: string }[] }>(await client.get('/api/markets?status=active&sort=newest&pageSize=50'), '市场列表');
      expect(page.items.map((i) => i.publicNo)).toContain(no);
      const d = await json<Detail>(await client.get(`/api/markets/${no}`), '市场详情');
      expect(d.status).toBe('listed');
    });

    await step('改费率 5% → 8%，修改记录公开', async () => {
      const r = await json<{ oldRate: string; newRate: string }>(await admin.put(`/api/admin/markets/${no}/fee`, { data: { feeRate: '8', reason: '端到端测试' } }), '改费率');
      expect(`${r.oldRate}>${r.newRate}`).toBe('5>8');
      const d = await json<Detail>(await client.get(`/api/markets/${no}`), '市场详情');
      expect(d.feeRate).toBe('8');
      expect(d.feeChanges.map((c) => `${c.oldRate}>${c.newRate}`)).toEqual(['5>8']);
    });

    await step('时钟拨到截止时间，截止任务跑一轮：没有下注，按 E4 作废', async () => {
      await json(await anon.post('/api/e2e/clock/advance', { data: { millis: 120_000 } }), '拨时钟');
      await json(await anon.post('/api/e2e/markets/tick'), '跑截止任务');
      await expect
        .poll(async () => (await json<Detail>(await client.get(`/api/markets/${no}`), '市场详情')).status, { message: '等市场截止', timeout: 20_000 })
        .toBe('voided');
      const d = await json<Detail>(await client.get(`/api/markets/${no}`), '市场详情');
      expect(d.voidReason).toBe('NOT_ENOUGH_OPTIONS');
      const after = await admin.put(`/api/admin/markets/${no}/fee`, { data: { feeRate: '6', reason: '作废后' } });
      expect(after.status(), '终点状态不能再改费率').toBe(409);
    });
    await admin.dispose();
    await client.dispose();
  } finally {
    await anon.post('/api/e2e/clock/reset');
    await anon.dispose();
  }
});
