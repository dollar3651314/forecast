/**
 * 推广功能第一批（forecast S13：MF-1、MF-3、MF-4、MF-6、MF-10；开发记录/S13-推广功能1.md）。两种形态都跑（tests/targets.ts）。
 * 1. 手机 390：不登录浏览市场列表、今日场次、详情（MF-4，AC-85）→ 点选项押注 → 去登录 → 登录后回到原来的市场、选中的选项直接弹出下注抽屉。
 * 2. 带来源参数打开网站（?src=&cmp=&ref=）→ 注册 → 后台"来源统计"按首次来源看到这个来源的访问和注册（MF-1，AC-80）；导出 CSV 写审计（MF-10）。
 * 3. 搜索引擎看到的页面（关掉 JS）：上架的市场有标题、描述、规范地址、语言版本和分享卡片的标签和正文；草稿是 404；
 *    robots.txt、sitemap.xml；短链接 /m/{编号} 跳到市场页（AC-85）。开奖记录、周报不登录可看（MF-6，AC-88）。
 */
import { expect, request, test, type APIRequestContext, type Page } from '@playwright/test';
import { adminSession, clientSession, json, listMarket, t } from './api';
import { adminLogin } from './admin';
import { CLIENT_PASSWORD, e2e, step, uniqueId } from './support';
import { ADMIN_URL, CLIENT_URL, DEPLOYED, STUB_URL } from './targets';

const mobile = { viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true } as const;

/** 在页面里注册（和 client.spec.ts 同样的填法）；注册后自动登录、跳过新手引导 */
async function registerInPage(page: Page, anon: APIRequestContext, email: string): Promise<void> {
  await page.goto('/register');
  await page.getByLabel('邮箱', { exact: true }).fill(email);
  const sentAt = new Date(Date.now() - 1000).toISOString();
  await page.getByRole('button', { name: '发送验证码' }).click();
  const code = await e2e.mailCode(anon, email, sentAt);
  await page.getByLabel('邮箱验证码').fill(code);
  await page.getByLabel('密码', { exact: true }).fill(CLIENT_PASSWORD);
  await page.getByLabel('确认密码').fill(CLIENT_PASSWORD);
  await page.getByLabel('名', { exact: true }).fill('来源');
  await page.getByLabel('姓', { exact: true }).fill('测试');
  const birth = page.getByRole('group', { name: '出生日期' });
  await birth.getByLabel('年', { exact: true }).fill('1990');
  await birth.getByLabel('月', { exact: true }).fill('01');
  await birth.getByLabel('日', { exact: true }).fill('27');
  await page.getByLabel('国籍').click();
  await page.getByRole('textbox', { name: '搜索' }).fill('SG');
  await page.getByRole('option').first().click();
  await page.getByRole('checkbox').check();
  await page.getByRole('button', { name: '注册', exact: true }).click();
  await expect(page).toHaveURL(/\/markets$/);
  const guide = page.getByRole('dialog').filter({ hasText: '新手引导' });
  await expect(guide).toBeVisible();
  await guide.getByRole('button', { name: '跳过', exact: true }).click();
}

test('手机 390：不登录浏览列表、今日场次、详情 → 点押注去登录 → 登录后回到原处、弹出下注抽屉', async ({ browser }) => {
  const anon = await request.newContext({ baseURL: STUB_URL });
  const ctx = await browser.newContext({ baseURL: CLIENT_URL, ignoreHTTPSErrors: DEPLOYED, ...mobile, locale: 'zh-CN', timezoneId: 'Asia/Shanghai', serviceWorkers: 'block' });
  const page = await ctx.newPage();
  try {
    // 今日场次按客户所在时区的"今天"取：时钟先回到系统时间（别的用例拨过时也不影响）
    await json(await anon.post('/api/e2e/clock/reset'), '时钟复原');
    const admin = await step('超管登录（接口）', () => adminSession(anon));
    const title = `游客浏览 ${uniqueId()}`;
    const m = await step('后台上架一个市场（2 分钟后开始）并加入今日场次', async () => {
      const r = await listMarket(anon, admin, title);
      const d = await json<{ todaySlate: boolean }>(await admin.post(`/api/admin/markets/${r.no}/today-slate`, { data: { todaySlate: true } }), '加入今日场次');
      expect(d.todaySlate).toBe(true);
      return r;
    });
    const client = await step('准备一个客户账户（接口注册）', () => clientSession(anon, 'guest'), 90_000);
    await client.api.dispose();

    await step('不登录打开市场列表：今日场次里有这个市场，列表能搜到；顶栏是"登录 / 注册"', async () => {
      await page.goto('/markets');
      const slate = page.getByTestId('today-slate');
      await expect(slate.getByTestId('slate-item').filter({ hasText: title })).toHaveCount(1);
      await expect(page.getByRole('link', { name: '登录' }).first()).toBeVisible();
      await page.getByRole('searchbox', { name: '搜索市场' }).fill(title);
      await expect(page.getByTestId('market-card').filter({ hasText: title })).toHaveCount(1);
    });
    await step('点卡片上的"否"进详情：不登录也能看规则和奖池，不弹下注抽屉', async () => {
      await page.getByTestId('market-card').filter({ hasText: title }).getByRole('button', { name: /^否/ }).click();
      await expect(page).toHaveURL(new RegExp(`/markets/${m.no}\\?opt=${m.nope}$`));
      await expect(page.getByRole('heading', { name: new RegExp(title) })).toBeVisible();
      await expect(page.getByRole('dialog')).toHaveCount(0);
    });
    await step('点"否"押注 → 去登录页；登录后回到这个市场，下注抽屉直接打开、选的是"否"', async () => {
      // 第二个选项"否"的押注按钮
      await page.getByRole('button', { name: '押注', exact: true }).nth(1).click();
      await expect(page).toHaveURL(/\/login$/);
      await page.getByLabel('邮箱', { exact: true }).fill(client.email);
      await page.getByLabel('密码', { exact: true }).fill(CLIENT_PASSWORD);
      await page.getByRole('button', { name: '登录', exact: true }).click();
      await expect(page).toHaveURL(new RegExp(`/markets/${m.no}`));
      const sheet = page.getByRole('dialog');
      await expect(sheet).toBeVisible();
      await expect(sheet).toContainText('否（简）');
      await expect(sheet.getByLabel('金额（USDT）')).toBeVisible();
    });
    await admin.dispose();
  } finally {
    await ctx.close();
    await anon.dispose();
  }
});

test('带来源参数打开网站 → 注册 → 后台"来源统计"看到这个来源；导出 CSV', async ({ browser }) => {
  const anon = await request.newContext({ baseURL: STUB_URL });
  const ctx = await browser.newContext({ baseURL: CLIENT_URL, ignoreHTTPSErrors: DEPLOYED, ...mobile, locale: 'zh-CN', timezoneId: 'Asia/Shanghai', serviceWorkers: 'block' });
  const page = await ctx.newPage();
  const actx = await browser.newContext({ baseURL: ADMIN_URL, ignoreHTTPSErrors: DEPLOYED, viewport: { width: 1440, height: 900 }, locale: 'zh-CN', serviceWorkers: 'block', acceptDownloads: true });
  const apage = await actx.newPage();
  const src = `e2e${uniqueId()}`.slice(0, 32);
  try {
    await step('带来源参数打开网站（Telegram 频道的推广链接）：记下首次来源和一次访问', async () => {
      const visit = page.waitForResponse((r) => r.url().includes('/api/attribution/visit') && r.request().method() === 'POST');
      await page.goto(`/markets?src=${src}&cmp=w43&ref=kol_e2e`);
      expect((await visit).status()).toBe(204);
      const saved = await page.evaluate(() => window.localStorage.getItem('fc.attr'));
      expect(saved).toContain(src);
    });
    const email = `src-${uniqueId()}@example.com`;
    await step('在页面里注册（注册请求带上来源）', async () => {
      const reg = page.waitForRequest((r) => r.url().endsWith('/api/auth/register') && r.method() === 'POST');
      await registerInPage(page, anon, email);
      const body = (await reg).postDataJSON() as { attribution?: { first?: { src?: string; cmp?: string; ref?: string } } };
      expect(body.attribution?.first).toMatchObject({ src, cmp: 'w43', ref: 'kol_e2e' });
      // 注册后清掉本机记下的来源（同一台设备以后注册的别的账户不再算这个来源）
      expect(await page.evaluate(() => window.localStorage.getItem('fc.attr'))).toBeNull();
    }, 120_000);
    await step('后台"来源统计"：这个来源有 1 次访问、1 个注册', async () => {
      await adminLogin(apage);
      await apage.goto('/admin/growth');
      await expect(apage.getByRole('heading', { name: '来源统计' })).toBeVisible();
      const row = apage.locator('tr').filter({ hasText: src });
      await expect(row).toHaveCount(1);
      await expect(row).toContainText('w43');
      await expect(row).toContainText('推荐 kol_e2e');
      const cells = await row.locator('td').allInnerTexts();
      expect(cells[2].trim(), '访问').toBe('1');
      expect(cells[3].trim(), '注册').toBe('1');
    });
    await step('导出"按客户"的 CSV：只有客户编号和来源，没有邮箱；写审计', async () => {
      const done = apage.waitForResponse((r) => r.url().includes('/api/admin/growth/export') && r.url().includes('kind=customers'));
      const dl = apage.waitForEvent('download');
      await apage.getByTestId('growth-export').hover();
      await apage.getByText('按客户（只有客户编号）').click();
      const res = await done;
      expect(res.status()).toBe(200);
      const text = (await res.body()).toString('utf8');
      expect(text).toContain(src);
      expect(text).not.toContain('@');
      expect((await dl).suggestedFilename()).toMatch(/^growth-customers-.*\.csv$/);
      const admin = await adminSession(anon);
      const audits = await json<{ items: { action: string; result: string }[] }>(await admin.get('/api/admin/audit-logs?action=growth.export&page=1&pageSize=5'), '审计');
      expect(audits.items.some((a) => a.action === 'growth.export' && a.result === 'success')).toBe(true);
      await admin.dispose();
    });
  } finally {
    await ctx.close();
    await actx.close();
    await anon.dispose();
  }
});

test('搜索引擎看到的页面（不跑 JS）：市场页有标签和正文、草稿 404、robots、sitemap、短链接；开奖记录和周报不登录可看', async ({ browser }) => {
  const anon = await request.newContext({ baseURL: STUB_URL });
  // 不跑 JS = 搜索引擎和分享卡片抓取看到的版本
  const ctx = await browser.newContext({ baseURL: CLIENT_URL, ignoreHTTPSErrors: DEPLOYED, javaScriptEnabled: false, serviceWorkers: 'block' });
  const page = await ctx.newPage();
  try {
    const admin = await step('超管登录（接口）', () => adminSession(anon));
    const title = `收录 ${uniqueId()}`;
    const m = await step('上架一个市场、另建一个草稿', () => listMarket(anon, admin, title));
    const draft = await step('草稿', async () => {
      const now = Date.now();
      const at = (ms: number) => new Date(now + ms).toISOString();
      const d = await json<{ publicNo: string }>(
        await admin.post('/api/admin/markets', {
          data: { category: 'MACRO', title: t(`草稿 ${title}`), rules: t('规则'), resolutionMode: 'PLATFORM', sourcePrimary: 'example.org', sourceBackup: 'example.net', openAt: at(60_000), closeAt: at(3_600_000), eventStartAt: at(3_600_000), eventEndAt: at(7_200_000), disputeWindowMinutes: 2, options: [{ label: t('是'), winCondition: t('是') }, { label: t('否'), winCondition: t('否') }] },
        }),
        '建草稿',
      );
      return d.publicNo;
    });
    await step('市场页（?lang=zh-CN）：标题、描述、规范地址、三种语言的版本、分享卡片标签；正文里有题目和选项', async () => {
      const res = await page.goto(`/markets/${m.no}?lang=zh-CN`);
      expect(res?.status()).toBe(200);
      const html = await page.content();
      expect(await page.title()).toContain(`${title}（简）`);
      expect(html).toMatch(new RegExp(`<link rel="canonical" href="[^"]*/markets/${m.no}\\?lang=zh-CN"`));
      expect(html).toContain('hreflang="en"');
      expect(html).toContain('hreflang="zh-TW"');
      expect(html).toContain('hreflang="x-default"');
      expect(html).toMatch(/<meta property="og:title" content="[^"]+"/);
      expect(html).toContain('og:description');
      await expect(page.locator('.seo-static')).toContainText(`${title}（简）`);
      await expect(page.locator('.seo-static')).toContainText('否（简）');
      // 没有客户的个人信息（邮箱）
      expect(html).not.toMatch(/[\w.+-]+@example\.com/);
    });
    await step('不带 lang 是英文版本（x-default）', async () => {
      await page.goto(`/markets/${m.no}`);
      expect(await page.title()).toContain(`${title} (en)`);
    });
    await step('草稿和不存在的市场：404（不给搜索引擎收录）', async () => {
      expect((await page.goto(`/markets/${draft}`))?.status()).toBe(404);
      expect((await page.goto('/markets/1234567890123'))?.status()).toBe(404);
    });
    await step('robots.txt 和 sitemap.xml：站点地图里有这个市场，没有草稿', async () => {
      await page.goto('/robots.txt');
      const robots = (await page.locator('body').innerText()).trim();
      expect(robots).toContain('User-agent: *');
      expect(robots).toMatch(/Sitemap: \S+\/sitemap\.xml/);
      await page.goto('/sitemap.xml');
      const xml = await page.content();
      expect(xml).toContain(`/markets/${m.no}`);
      expect(xml).not.toContain(`/markets/${draft}`);
      expect(xml).toContain('/results');
    });
    await step('短链接 /m/{编号}：301 跳到市场页', async () => {
      const r = await page.goto(`/m/${m.no}`);
      expect(r?.url()).toMatch(new RegExp(`/markets/${m.no}$`));
      expect(r?.request().redirectedFrom()?.url()).toMatch(new RegExp(`/m/${m.no}$`));
    });
    await step('开奖记录、周报：不登录能取到（接口），页面有服务器给的正文', async () => {
      const results = await json<{ items: unknown[] }>(await anon.get('/api/public/results?page=1&pageSize=5'), '开奖记录');
      expect(Array.isArray(results.items)).toBe(true);
      const weeks = await json<{ items: { week: string }[] }>(await anon.get('/api/public/weekly?page=1'), '周报');
      expect(weeks.items.length).toBeGreaterThan(0);
      expect((await page.goto('/results'))?.status()).toBe(200);
      await expect(page.locator('.seo-static h1')).toBeVisible();
      expect((await page.goto(`/results/weekly/${weeks.items[0].week}`))?.status()).toBe(200);
      expect((await page.goto('/results/weekly/2026-W99'))?.status()).toBe(404);
      expect((await page.goto('/how-to-play'))?.status()).toBe(200);
      await expect(page.locator('.seo-static')).toContainText('USDT');
    });
    await admin.dispose();
  } finally {
    await ctx.close();
    await anon.dispose();
  }
});
