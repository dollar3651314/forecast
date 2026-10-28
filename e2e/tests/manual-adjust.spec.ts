/**
 * 人工调账的端到端（forecast S17B，需求说明书第 21 节；生产可用，两种形态都跑同一条路）：
 * 后台客户详情"人工加款"（充值未到账补入、参考号）→ 客户端登录后资产页的余额、资金流水"人工调整（加款） · 充值未到账补入"、站内信 #24 →
 * "人工扣款"超过可用余额被拒（弹窗不关、显示原因）→ 扣一部分 → "资金 → 人工调账记录"能查到、导出 CSV（同源 fetch，写审计）。
 */
import { expect, request, test, type Page } from '@playwright/test';
import { ADMIN_URL, CLIENT_URL, DEPLOYED, STUB_URL } from './targets';
import { CLIENT_PASSWORD, e2e, step } from './support';
import { adminLogin, cjk } from './admin';
import { adminSession, clientSession, json } from './api';

async function openDialog(page: Page, testId: string) {
  await page.getByTestId(testId).click();
  const dialog = page.locator('.ant-modal').filter({ hasText: '操作原因' });
  await expect(dialog).toBeVisible();
  await expect(page.locator('.ant-modal[class*="-appear"], .ant-modal[class*="-enter"]')).toHaveCount(0);
  return dialog;
}

test('人工调账：后台加款、扣款 → 客户端余额、流水和通知 → 记录列表和导出', async ({ browser }) => {
  const anon = await request.newContext({ baseURL: STUB_URL });
  const admin = await step('超管登录（接口）', () => adminSession(anon));
  const customer = await step('注册客户', () => clientSession(anon, 'adj'), 90_000);
  const found = await json<{ items: { id: number; email: string }[] }>(
    await admin.get(`/api/admin/customers?keyword=${encodeURIComponent(customer.email)}`),
    '按邮箱找客户',
  );
  const id = found.items.find((c) => c.email === customer.email)!.id;
  const adminCtx = await browser.newContext({ baseURL: ADMIN_URL, ignoreHTTPSErrors: DEPLOYED, viewport: { width: 1440, height: 900 }, locale: 'zh-CN', serviceWorkers: 'block' });
  const adminPage = await adminCtx.newPage();
  const clientCtx = await browser.newContext({ baseURL: CLIENT_URL, ignoreHTTPSErrors: DEPLOYED, viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, locale: 'zh-CN', serviceWorkers: 'block' });
  const page = await clientCtx.newPage();
  try {
    await step('后台：超管登录（页面）', async () => {
      await adminLogin(adminPage);
    });

    await step('后台：人工加款 500（充值未到账补入、参考号）', async () => {
      await adminPage.goto(`/admin/customers/${id}`);
      await expect(adminPage.getByText(customer.email).first()).toBeVisible();
      const dialog = await openDialog(adminPage, 'adjust-credit');
      await dialog.getByLabel('金额（USDT）').fill('500');
      await expect(dialog.getByLabel('充值未到账补入')).toBeChecked();
      await dialog.getByLabel('参考号（选填）').fill('0xe2e0000000000000000000000000000000000000000000000000000000000001');
      await dialog.getByLabel('操作原因').fill('端到端：TRC20 充值没识别');
      await dialog.getByText('我已核对操作对象').click();
      const done = adminPage.waitForResponse((r) => r.url().includes(`/customers/${id}/adjust`) && r.request().method() === 'POST', { timeout: 15_000 });
      await dialog.getByRole('button', { name: cjk('确认加款') }).click();
      const res = await done;
      expect(res.ok(), `返回 ${res.status()}：${await res.text()}`).toBe(true);
      await expect(dialog).toBeHidden();
      await expect(adminPage.getByText(/已加款 500\.00 USDT，可用余额 0\.00 → 500\.00/)).toBeVisible();
    });

    await step('后台：人工扣款超过可用余额被拒（弹窗不关、显示原因），扣 120 成功', async () => {
      const dialog = await openDialog(adminPage, 'adjust-debit');
      await expect(dialog.getByLabel('误入账扣回')).toBeChecked();
      await dialog.getByLabel('金额（USDT）').fill('600');
      await dialog.getByLabel('操作原因').fill('端到端：扣太多');
      await dialog.getByText('我已核对操作对象').click();
      await dialog.getByRole('button', { name: cjk('确认扣款') }).click();
      await expect(dialog.getByText(/不能超过可用余额 500\.00 USDT/)).toBeVisible();
      await dialog.getByLabel('金额（USDT）').fill('120');
      const done = adminPage.waitForResponse((r) => r.url().includes(`/customers/${id}/adjust`) && r.request().method() === 'POST', { timeout: 15_000 });
      await dialog.getByRole('button', { name: cjk('确认扣款') }).click();
      expect((await done).ok()).toBe(true);
      await expect(dialog).toBeHidden();
      await expect(adminPage.getByText(/已扣款 120\.00 USDT，可用余额 500\.00 → 380\.00/)).toBeVisible();
    });

    await step('客户端：登录后余额 380、资金流水两条人工调整（带类别）、站内信', async () => {
      await page.goto('/login');
      await page.getByLabel('邮箱', { exact: true }).fill(customer.email);
      await page.getByLabel('密码', { exact: true }).fill(CLIENT_PASSWORD);
      await page.getByRole('button', { name: '登录', exact: true }).click();
      await expect(page).toHaveURL(/\/markets$/);
      const guide = page.getByRole('dialog').filter({ hasText: '新手引导' });
      if (await guide.isVisible().catch(() => false)) await guide.getByRole('button', { name: '跳过', exact: true }).click();
      await page.goto('/assets');
      await expect(page.getByText('380.00').first()).toBeVisible();
      await page.goto('/assets/ledger');
      const list = page.locator('.ledger');
      await expect(list.getByText('人工调整（加款） · 充值未到账补入')).toBeVisible();
      await expect(list.getByText('人工调整（扣款） · 误入账扣回')).toBeVisible();
      await expect(list.getByText('+500.00')).toBeVisible();
      await page.goto('/me/notifications');
      await expect(page.getByText('你的余额已调整：加款 500.00 USDT')).toBeVisible();
      await expect(page.getByText('你的余额已调整：扣款 120.00 USDT')).toBeVisible();
    });

    await step('邮件：#24 发到客户邮箱', async () => {
      await expect
        .poll(async () => (await e2e.mailSubjects(anon, customer.email)).join(' | '), { message: '等余额调整的邮件', timeout: 20_000 })
        .toContain('你的余额已调整');
    });

    await step('后台：资金 → 人工调账记录（按客户筛选）、导出 CSV', async () => {
      await adminPage.goto('/admin/funds/adjustments');
      await adminPage.locator('#adj-customer').fill(String(id));
      await adminPage.getByRole('button', { name: cjk('查询') }).click();
      const rows = adminPage.getByRole('row').filter({ hasText: customer.email });
      await expect(rows).toHaveCount(2);
      await expect(rows.filter({ hasText: '充值未到账补入' })).toContainText('端到端：TRC20 充值没识别');
      const csv = await adminPage.evaluate(async (cid) => {
        const res = await fetch(`/api/admin/balance-adjustments/export?customerId=${cid}`, { credentials: 'same-origin' });
        return { status: res.status, text: await res.text() };
      }, String(id));
      expect(csv.status).toBe(200);
      expect(csv.text).toContain('充值未到账补入');
      expect(csv.text.trim().split('\n')).toHaveLength(3);
    });
  } finally {
    await adminCtx.close();
    await clientCtx.close();
    await admin.dispose();
    await customer.api.dispose();
    await anon.dispose();
  }
});
