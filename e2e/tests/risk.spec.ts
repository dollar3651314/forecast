/**
 * 风控告警和员工账户的端到端（forecast S10，需求第 8 节、AC-67、AC-68）：真实后端 + 真实接口模式的后台。
 * 两个客户用同一个设备号登录（关联账户）→ 截止前 2 分钟在同一市场押不同的选项（尾盘大额 + 关联账户对押）→ 风控扫描 →
 * 后台"风控告警"页能按客户筛出来、标记误报（说明必填）→ 客户详情看到关联账户 → 登记为员工（高危）当场退回下注 → 再下注被拒。
 * 客户和下注走接口；两种形态都跑。
 */
import { expect, request, test, type Page } from '@playwright/test';
import { ADMIN_URL, DEPLOYED, STUB_URL } from './targets';
import { step, uniqueId, CLIENT_PASSWORD } from './support';
import { adminLogin, cjk } from './admin';
import { bet, clientSession, cookieOf, fund, json, listMarket, adminSession } from './api';

type Alert = { id: string; kind: string; userId: string; status: string };
type Page_<T> = { items: T[]; total: number };

async function highRisk(page: Page, reason: string, okName: RegExp, urlPart: string): Promise<void> {
  const dialog = page.locator('.ant-modal').filter({ hasText: '操作原因' });
  await expect(dialog).toBeVisible();
  await expect(page.locator('.ant-modal[class*="-appear"], .ant-modal[class*="-enter"]')).toHaveCount(0);
  await dialog.getByLabel('操作原因').fill(reason);
  await dialog.getByText('我已核对操作对象').click();
  const done = page.waitForResponse((r) => r.url().includes(urlPart) && r.request().method() === 'POST', { timeout: 15_000 });
  await dialog.getByRole('button', { name: okName }).click();
  const res = await done;
  expect(res.ok(), `${urlPart} 返回 ${res.status()}：${await res.text()}`).toBe(true);
  await expect(dialog).toBeHidden();
}

test('风控告警：关联账户对押、尾盘大额 → 后台处理；员工登记退回下注、不能再下注', async ({ browser }) => {
  const anon = await request.newContext({ baseURL: STUB_URL });
  const ctx = await browser.newContext({ baseURL: ADMIN_URL, ignoreHTTPSErrors: DEPLOYED, viewport: { width: 1440, height: 900 }, locale: 'zh-CN', serviceWorkers: 'block' });
  const page = await ctx.newPage();
  try {
    const admin = await step('接口：超管会话', () => adminSession(anon));
    const a = await step('注册客户 A', () => clientSession(anon, 'rka'), 90_000);
    const b = await step('注册客户 B', () => clientSession(anon, 'rkb'), 90_000);
    const device = `e2e-device-${uniqueId()}`;
    await step('A、B 用同一个设备号登录（请求头 X-Device-Id）', async () => {
      for (const c of [a, b]) {
        const r = await anon.post('/api/auth/login', { data: { email: c.email, password: CLIENT_PASSWORD }, headers: { 'X-Device-Id': device } });
        await json(r, '登录');
        expect(cookieOf(r, 'fc_session')).toBeTruthy();
      }
    });
    await step('两人各充值 100', async () => {
      await fund(anon, a.api, '100');
      await fund(anon, b.api, '100');
    });
    // SQ-24（S16）：尾盘大额只看单笔 ≥ risk.lateBet.minAmount（默认 100）；这里两人只充了 100，临时调成 50，用例结束改回 100
    await step('参数设置：尾盘大额的单笔最低金额调成 50', async () => {
      await json(await admin.put('/api/admin/params/betting/risk.lateBet.minAmount', { data: { value: '50', reason: '端到端：风控用例' } }), '改参数');
    });
    const m = await step('上架一个 2 分钟后截止的市场', () => listMarket(anon, admin, `端到端风控 ${Date.now()}`));
    await step('截止前 2 分钟：A 押"是" 60、B 押"否" 40；扫描风控', async () => {
      await bet(a.api, m.no, m.yes, '60');
      await bet(b.api, m.no, m.nope, '40');
      await json(await anon.post('/api/e2e/riskalert/scan'), '风控扫描');
      const aId = (await json<{ userId: string }>(await a.api.get('/api/me/session'), 'A 会话')).userId;
      const list = await json<Page_<Alert>>(await admin.get(`/api/admin/risk-alerts?status=new&keyword=${encodeURIComponent(a.email)}`), '告警');
      expect(list.items.filter((x) => x.userId === aId).map((x) => x.kind).sort()).toEqual(['late_large_bet', 'linked_opposite']);
    });

    await step('后台"风控告警"页：按客户筛选，看到两条；关联对押标记误报（说明必填）', async () => {
      await adminLogin(page);
      await expect(page.locator('.ant-layout-sider').getByText('风控告警', { exact: true }).first()).toBeVisible();
      await page.goto('/admin/risk/alerts');
      await page.getByLabel('客户').fill(a.email);
      await page.getByRole('button', { name: cjk('查询') }).click();
      const rows = page.locator('tbody tr').filter({ hasText: 'Market Tester' });
      await expect(rows).toHaveCount(2);
      await expect(rows.filter({ hasText: '尾盘大额' })).toContainText(/截止前 \d+ 秒下注，占下注后奖池 100\.0%/);
      const linked = rows.filter({ hasText: '关联账户对押' });
      await linked.getByRole('button', { name: cjk('误报') }).click();
      const dialog = page.locator('.ant-modal').filter({ hasText: '处理说明' });
      await expect(dialog).toBeVisible();
      await dialog.getByRole('textbox').fill('端到端测试：同一个人的两个测试账户');
      const done = page.waitForResponse((r) => r.url().includes('/risk-alerts/') && r.url().endsWith('/status'));
      await dialog.getByRole('button', { name: /确\s*定|确认|提交/ }).click();
      expect((await done).ok()).toBe(true);
      await expect(page.locator('tbody tr').filter({ hasText: 'Market Tester' })).toHaveCount(1);
    });

    const aId = (await json<{ userId: string }>(await a.api.get('/api/me/session'), 'A 会话')).userId;
    await step('客户详情：关联账户是 B（同设备）；登记为员工（高危）→ 当场退回 A 的下注 60', async () => {
      await page.goto(`/admin/customers/${aId}`);
      await expect(page.getByText('同设备')).toBeVisible();
      await page.getByRole('button', { name: /登记为员工/ }).click();
      await highRisk(page, '端到端：运营同事的账户', cjk('确认登记'), '/staff');
      await expect(page.getByText('已退回 1 个市场的 1 笔下注，合计 60.00 USDT')).toBeVisible();
      await expect(page.getByText('这是登记的员工账户：不能下注')).toBeVisible();
      await expect
        .poll(async () => (await json<{ available: string }>(await a.api.get('/api/ledger/account'), '账户')).available, { message: '等退回' })
        .toBe('100');
    });

    await step('员工账户下注被拒（AC-67）', async () => {
      const r = await a.api.post(`/api/markets/${m.no}/bets`, { data: { optionNo: m.yes, amount: '1', clientReqId: `rk-${uniqueId()}` } });
      expect(r.status()).toBe(400);
      expect((await r.json()).code).toBe('BET_STAFF_ACCOUNT');
    });
  } finally {
    const admin = await adminSession(anon).catch(() => null);
    if (admin) await admin.put('/api/admin/params/betting/risk.lateBet.minAmount', { data: { value: '100', reason: '端到端：改回默认' } }).catch(() => undefined);
    await ctx.close();
  }
});
