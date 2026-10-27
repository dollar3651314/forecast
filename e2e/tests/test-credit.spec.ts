/**
 * 测试入金的端到端（forecast S17A，开发记录/S17-测试入金和人工调账.md 第 1.6 节）。
 * 测试入金只在测试环境（后端 FC_ENV_LABEL=test）存在，所以这个用例按后端健康检查里的 env 走两条路：
 * - **测试环境**（部署形态：e2e/compose.deployed.yml 和测试环境一样写了 FC_ENV_LABEL=test；默认形态起后端前设 FC_ENV_LABEL=test 也走这里）：
 *   后台客户详情 → "测试入金"（金额、原因、勾选）→ 客户端登录后看到可用余额和资金流水里的"测试入金" → 后台报表、对账页单独一行；
 *   同一个确认框重复提交只入一次（请求号）。
 * - **生产形态**（默认形态的后端不设 FC_ENV_LABEL，和生产一样）：后台客户详情没有按钮，接口 404（同源 fetch，和页面调接口一样）。
 * CI 的 e2e（默认形态）走第二条，e2e-deployed（部署形态）走第一条，两条都覆盖。
 */
import { expect, request, test, type Page } from '@playwright/test';
import { ADMIN_URL, CLIENT_URL, DEPLOYED, STUB_URL } from './targets';
import { CLIENT_PASSWORD, step } from './support';
import { adminLogin, cjk } from './admin';
import { adminSession, clientSession, json } from './api';

/** 在后台页面里同源调接口（部署形态的假域名只有浏览器能解析） */
async function pageFetch(page: Page, method: string, path: string, body?: unknown): Promise<{ status: number; text: string }> {
  return page.evaluate(
    async ({ method, path, body }) => {
      const res = await fetch(path, {
        method,
        credentials: 'same-origin',
        headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
        body: body === undefined ? undefined : JSON.stringify(body),
      });
      return { status: res.status, text: await res.text() };
    },
    { method, path, body },
  );
}

test('测试入金：测试环境后台入金、客户端看到余额和流水；生产形态没有按钮、接口 404', async ({ browser }) => {
  const anon = await request.newContext({ baseURL: STUB_URL });
  const health = await json<{ env?: string }>(await anon.get('/api/health'), '健康检查');
  const testEnv = health.env === 'test';
  test.info().annotations.push({ type: '后端环境标识', description: testEnv ? 'test（测试环境）' : '没有（生产形态）' });
  const admin = await step('超管登录（接口）', () => adminSession(anon));
  const customer = await step('注册客户', () => clientSession(anon, 'tc'), 90_000);
  const found = await json<{ items: { id: number; email: string }[] }>(
    await admin.get(`/api/admin/customers?keyword=${encodeURIComponent(customer.email)}`),
    '按邮箱找客户',
  );
  const id = found.items.find((c) => c.email === customer.email)!.id;

  const adminCtx = await browser.newContext({ baseURL: ADMIN_URL, ignoreHTTPSErrors: DEPLOYED, viewport: { width: 1440, height: 900 }, locale: 'zh-CN', serviceWorkers: 'block' });
  const adminPage = await adminCtx.newPage();
  const clientCtx = await browser.newContext({
    baseURL: CLIENT_URL,
    ignoreHTTPSErrors: DEPLOYED,
    viewport: { width: 390, height: 844 },
    isMobile: true,
    hasTouch: true,
    locale: 'zh-CN',
    serviceWorkers: 'block',
  });
  const page = await clientCtx.newPage();
  try {
    await step('后台：超管登录（页面）', async () => {
      await adminLogin(adminPage);
    });

    if (!testEnv) {
      await step('生产形态：客户详情没有"测试入金"按钮，接口 404', async () => {
        await adminPage.goto(`/admin/customers/${id}`);
        await expect(adminPage.getByText(customer.email).first()).toBeVisible();
        await expect(adminPage.getByText('最近资金流水')).toBeVisible();
        await expect(adminPage.getByTestId('test-credit')).toHaveCount(0);
        const r = await pageFetch(adminPage, 'POST', `/api/admin/customers/${id}/test-credit`, {
          amount: '100',
          reason: '生产形态不能入金',
          requestId: `e2e-${Date.now()}`,
          checked: true,
        });
        expect(r.status, r.text).toBe(404);
        const acc = await json<{ available: string }>(await customer.api.get('/api/ledger/account'), '账户');
        expect(acc.available).toBe('0');
      });
      return;
    }

    await step('测试环境：后台客户详情 → 测试入金 1,234.5（金额、原因、勾选确认）', async () => {
      await adminPage.goto(`/admin/customers/${id}`);
      await expect(adminPage.getByText(customer.email).first()).toBeVisible();
      await adminPage.getByTestId('test-credit').click();
      const dialog = adminPage.locator('.ant-modal').filter({ hasText: '操作原因' });
      await expect(dialog).toBeVisible();
      await expect(adminPage.locator('.ant-modal[class*="-appear"], .ant-modal[class*="-enter"]')).toHaveCount(0);
      await expect(dialog.getByText(/单笔上限 10,000\.00 USDT/)).toBeVisible();
      await dialog.getByLabel('入金金额（USDT）').fill('1234.5');
      await dialog.getByLabel('操作原因').fill('端到端：测试下注到提现');
      await dialog.getByText('我已核对操作对象').click();
      const done = adminPage.waitForResponse((r) => r.url().includes('/test-credit') && r.request().method() === 'POST', { timeout: 15_000 });
      await dialog.getByRole('button', { name: cjk('确认入金') }).click();
      const res = await done;
      expect(res.ok(), `返回 ${res.status()}：${await res.text()}`).toBe(true);
      await expect(dialog).toBeHidden();
      await expect(adminPage.getByText(/已入金 1,234\.50 USDT，可用余额 0\.00 → 1,234\.50/)).toBeVisible();
      // 后台的最近资金流水里是"测试入金"
      await expect(adminPage.locator('.ant-table').getByText('测试入金').first()).toBeVisible();
    });

    await step('重复提交同一个请求号只入一次', async () => {
      const requestId = `e2e-dup-${Date.now()}`;
      const body = { amount: '10', reason: '端到端：重复点击', requestId, checked: true };
      const a = await pageFetch(adminPage, 'POST', `/api/admin/customers/${id}/test-credit`, body);
      const b = await pageFetch(adminPage, 'POST', `/api/admin/customers/${id}/test-credit`, body);
      expect(a.status, a.text).toBe(200);
      expect(b.status, b.text).toBe(200);
      expect(JSON.parse(b.text).credit.duplicate).toBe(true);
      const acc = await json<{ available: string }>(await customer.api.get('/api/ledger/account'), '账户');
      expect(acc.available).toBe('1244.5');
    });

    await step('客户端：登录后资产页的可用余额、资金流水里的"测试入金"', async () => {
      await page.goto('/login');
      await page.getByLabel('邮箱', { exact: true }).fill(customer.email);
      await page.getByLabel('密码', { exact: true }).fill(CLIENT_PASSWORD);
      await page.getByRole('button', { name: '登录', exact: true }).click();
      await expect(page).toHaveURL(/\/markets$/);
      const guide = page.getByRole('dialog').filter({ hasText: '新手引导' });
      if (await guide.isVisible().catch(() => false)) await guide.getByRole('button', { name: '跳过', exact: true }).click();
      await page.goto('/assets');
      await expect(page.getByText('1,244.50').first()).toBeVisible();
      await page.goto('/assets/ledger');
      const list = page.locator('.ledger');
      await expect(list.getByText(/^测试入金$/)).toHaveCount(2);
      await expect(list.getByText('+1,234.50')).toBeVisible();
      // 不和真实充值混在一起
      await expect(list.getByText(/^充值( · |$)/)).toHaveCount(0);
    });

    await step('后台：对账页"平台账户"里测试入金单独一行，余额 = 账本合计；报表单独一列', async () => {
      await adminPage.goto('/admin/funds/reconciliation');
      const row = adminPage.getByRole('row').filter({ hasText: '测试入金' });
      await expect(row).toContainText('一致');
      await expect(row).toContainText('-');
      await adminPage.goto('/admin/reports');
      await expect(adminPage.locator('.kpi').filter({ hasText: '测试入金' })).toBeVisible();
    });
  } finally {
    await adminCtx.close();
    await clientCtx.close();
    await admin.dispose();
    await customer.api.dispose();
    await anon.dispose();
  }
});
