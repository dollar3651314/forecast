/**
 * 告警邮件控制的端到端（forecast S12b，Amos 2026-10-27："告警邮件，我可以在什么地方控制 是否发送"）。两种形态都跑：
 * 1. "未开通"（Telegram 机器人没配 token）不发邮件；"配置了但出错"的项（端到端环境里有，例如没配发起账户的 sweep.config）发一封；
 * 2. 后台"参数设置 → 告警邮件"在页面上关掉总开关（高危确认）→ 用 e2e 邮件替身确认不发；
 * 3. 在页面上把一项勾进"不发邮件的告警项"，打开总开关 → 这一项不发、别的项照常。
 * 告警检查用 /api/e2e/alerts/check 立即触发（真实的检查、真实的后台开关、真实的邮件任务，收件人换成这个用例的邮箱，第一次调用就像应用刚启动）。
 */
import { expect, request, test, type APIRequestContext, type Page } from '@playwright/test';
import { ADMIN_URL, DEPLOYED, STUB_URL } from './targets';
import { e2e, step, uniqueId } from './support';
import { adminLogin, cjk } from './admin';
import { adminSession, json } from './api';

type AlertItem = { name: string; titleZh: string | null; status: 'UP' | 'DOWN' | 'NOT_CONFIGURED' | null; muted: boolean };

async function highRisk(page: Page, reason: string): Promise<void> {
  const dialog = page.locator('.ant-modal').filter({ hasText: '操作原因' });
  await expect(dialog).toBeVisible();
  await expect(page.locator('.ant-modal[class*="-appear"], .ant-modal[class*="-enter"]')).toHaveCount(0);
  await dialog.getByLabel('操作原因').fill(reason);
  await dialog.getByText('我已核对操作对象').click();
  const done = page.waitForResponse((r) => r.url().includes('/params/alert/') && r.request().method() === 'PUT', { timeout: 15_000 });
  await dialog.getByRole('button', { name: cjk('确认执行') }).click();
  const res = await done;
  expect(res.ok(), `返回 ${res.status()}：${await res.text()}`).toBe(true);
  await expect(dialog).toBeHidden();
}

async function check(anon: APIRequestContext, email: string): Promise<void> {
  await json(await anon.post('/api/e2e/alerts/check', { data: { email } }), '告警检查');
}

/** 等邮件任务发完（发送任务每 2 秒一次）：再等 6 秒，标题不再变化 */
async function settledSubjects(anon: APIRequestContext, email: string): Promise<string[]> {
  await new Promise((r) => setTimeout(r, 6_000));
  return e2e.mailSubjects(anon, email);
}

test('告警邮件：未开通不发、出错发一封；后台关掉总开关不发；静音的项不发', async ({ browser }) => {
  const anon = await request.newContext({ baseURL: STUB_URL });
  const ctx = await browser.newContext({ baseURL: ADMIN_URL, ignoreHTTPSErrors: DEPLOYED, viewport: { width: 1440, height: 900 }, locale: 'zh-CN', serviceWorkers: 'block' });
  const page = await ctx.newPage();
  const admin = await adminSession(anon);
  try {
    const items = await step('模拟没配 Telegram token；后台的告警项列表：telegram.bot 是"未开通"，至少有一项是出错', async () => {
      await json(await anon.post('/api/e2e/telegram/configured', { data: { configured: false } }), '去掉 token');
      const list = await json<AlertItem[]>(await admin.get('/api/admin/params/alert/items'), '告警项');
      expect(list.find((i) => i.name === 'telegram.bot')?.status).toBe('NOT_CONFIGURED');
      expect(list.every((i) => !i.muted), '默认没有静音的项').toBe(true);
      return list;
    });
    const down = items.filter((i) => i.status === 'DOWN');
    expect(down.length, `端到端环境里出错的告警项：${JSON.stringify(items)}`).toBeGreaterThan(0);

    await step('总开关开着：出错的每项发一封，"未开通"的 Telegram 机器人不发', async () => {
      const email = `alert_${uniqueId()}@example.com`;
      await check(anon, email);
      await expect.poll(async () => (await e2e.mailSubjects(anon, email)).length, { message: '等告警邮件', timeout: 20_000 }).toBe(down.length);
      const subjects = await settledSubjects(anon, email);
      expect(subjects).toHaveLength(down.length);
      for (const d of down) expect(subjects.some((s) => s.includes(`告警：${d.titleZh}`)), `${d.name} 的告警邮件：${subjects.join(' | ')}`).toBe(true);
      expect(subjects.some((s) => s.includes('Telegram')), '未开通的不发').toBe(false);
      await check(anon, email);
      expect(await settledSubjects(anon, email), '持续异常：不重复发').toHaveLength(down.length);
    });

    await step('后台页面：参数设置 → 告警邮件 → 关掉总开关（高危确认）', async () => {
      await adminLogin(page);
      await page.goto('/admin/params');
      const card = page.locator('.ant-card').filter({ has: page.locator('.ant-card-head-title', { hasText: /^告警邮件$/ }) });
      await expect(card.getByText('开（发邮件）')).toBeVisible();
      await card.getByRole('button', { name: '编辑 告警邮件总开关' }).click();
      const dialog = page.locator('.ant-modal').filter({ hasText: '操作原因' });
      await dialog.getByText('关（只写日志）').click();
      await highRisk(page, '端到端：测试阶段不收告警邮件');
      await expect(card.getByText('关（只写日志）')).toBeVisible();
    });

    const email2 = `alert_${uniqueId()}@example.com`;
    await step('总开关关掉：e2e 邮件替身里一封都没有', async () => {
      // 后台改了以后本机立即生效（清缓存）
      await check(anon, email2);
      expect(await settledSubjects(anon, email2)).toHaveLength(0);
    });

    await step(`后台页面：把 ${down[0].name} 勾进"不发邮件的告警项"，再打开总开关`, async () => {
      const card = page.locator('.ant-card').filter({ has: page.locator('.ant-card-head-title', { hasText: /^告警邮件$/ }) });
      await card.getByRole('button', { name: '编辑 不发邮件的告警项' }).click();
      const dialog = page.locator('.ant-modal').filter({ hasText: '操作原因' });
      const item = dialog.getByTestId(`alert-item-${down[0].name}`);
      await expect(item).toContainText('出错');
      await expect(dialog.getByTestId('alert-item-telegram.bot')).toContainText('未开通');
      await item.click();
      await highRisk(page, `端到端：${down[0].name} 不发邮件`);
      await expect(card.getByText('1 项')).toBeVisible();
      await expect(card.getByTestId('pm-muted-list')).toContainText(down[0].name);
      await card.getByRole('button', { name: '编辑 告警邮件总开关' }).click();
      await page.locator('.ant-modal').filter({ hasText: '操作原因' }).getByText('开（发邮件）').click();
      await highRisk(page, '端到端：打开');
      await expect(card.getByText('开（发邮件）')).toBeVisible();
    });

    await step('静音的项不发，别的出错的项照常补发（这次异常还没发过）', async () => {
      await check(anon, email2);
      const subjects = await settledSubjects(anon, email2);
      expect(subjects).toHaveLength(down.length - 1);
      expect(subjects.some((s) => s.includes(`告警：${down[0].titleZh}`)), '静音的项').toBe(false);
      for (const d of down.slice(1)) expect(subjects.some((s) => s.includes(`告警：${d.titleZh}`))).toBe(true);
    });

    await step('对照：取消静音后，同一个邮箱马上收到这一项的告警（说明前面没收到是开关起了作用，不是邮件没走通）', async () => {
      await json(await admin.put('/api/admin/params/alert/alert.mail.muted', { data: { value: '', reason: '端到端：取消静音' } }), '取消静音');
      await check(anon, email2);
      await expect
        .poll(async () => (await e2e.mailSubjects(anon, email2)).filter((s) => s.includes(`告警：${down[0].titleZh}`)).length, { message: '等补发的告警', timeout: 20_000 })
        .toBe(1);
      expect(await settledSubjects(anon, email2)).toHaveLength(down.length);
    });
  } finally {
    await admin.put('/api/admin/params/alert/alert.mail.enabled', { data: { value: 'true', reason: '端到端结束：改回' } }).catch(() => undefined);
    await admin.put('/api/admin/params/alert/alert.mail.muted', { data: { value: '', reason: '端到端结束：改回' } }).catch(() => undefined);
    await anon.post('/api/e2e/telegram/configured', { data: { configured: true } }).catch(() => undefined);
    await admin.dispose();
    await ctx.close();
    await anon.dispose();
  }
});
