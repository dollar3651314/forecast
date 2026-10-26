/**
 * 事件和文章自动化（forecast S15；需求文档/v1.3-事件和文章自动化.md，开发记录/S15-自动化程序.md）。两种形态都跑（tests/targets.ts）。
 * <b>不连真实的 Gemini</b>：e2e 配置里 Gemini 的地址指向后端自己的替身（/api/e2e/gemini，content/web/E2eContent），走真实的客户端、起草、检查和发布流程。
 * 1. 经济日历：录入一条美国 CPI（发布前 7 天以内）→ 跑一轮定时任务 → 按模板生成市场草稿，直接进"待上架"（不自动上架，AC-100、AC-102）。
 * 2. 文章：后台"生成草稿"选文章 → 替身起草中英文 → 数字核对通过 → Amos 在草稿箱点"发布"（高危确认）→ /insights/article-… 页面能打开
 *    （带 JS、不带 JS 的搜索引擎版本都有正文），站点地图里有它（AC-104、AC-111、AC-114）。
 * 3. 数字对不上：替身多写一个引用数据里没有的数字 → 重写一次仍对不上 → 待审核但标红、不能发布；接口直接发布也被拒；改掉后检查通过（AC-107）。
 * 4. 没有 key：后台提示"未配置"，AI 起草返回"暂时用不了"；经济日历草稿、BTC 统计页照常（AC-115 降级）。
 * 设了 E2E_SHOTS_DIR 时顺便截后台内容中心和公开文章页的图（交付截图用）。
 */
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { expect, request, test, type APIRequestContext, type Page } from '@playwright/test';
import { adminSession, json } from './api';
import { adminLogin, cjk } from './admin';
import { step, uniqueId } from './support';
import { ADMIN_URL, CLIENT_URL, DEPLOYED, STUB_URL } from './targets';

type Draft = { draftNo: string; groupNo: string; kind: string; lang: 'zh' | 'en'; title: string; body: string; status: string; checkOk: boolean; version: number;
  checks: { numbers: { text: string }[]; summary: string } | null; siteUrl: string | null };
type Econ = { id: string; kind: string; period: string; status: string; marketNo: string | null; marketStatus: string | null; note: string | null };

const SHOTS = process.env.E2E_SHOTS_DIR;
if (SHOTS) mkdirSync(SHOTS, { recursive: true });

async function shot(page: Page, name: string): Promise<void> {
  if (!SHOTS) return;
  // 等加载动画、抽屉和弹窗的进出场动画结束再截
  await expect(page.locator('.ant-spin-spinning, .ant-skeleton-active')).toHaveCount(0);
  await expect(page.locator('[class*="-motion-"][class*="-active"], .ant-modal[class*="-appear"], .ant-modal[class*="-enter"]')).toHaveCount(0);
  await page.waitForTimeout(400);
  await page.screenshot({ path: join(SHOTS, `${name}.png`), fullPage: true });
}

async function geminiMode(anon: APIRequestContext, data: { mode?: string; key?: boolean }): Promise<void> {
  await json(await anon.post('/api/e2e/gemini/mode', { data }), `替身模式 ${JSON.stringify(data)}`);
}

async function setting(admin: APIRequestContext, values: Record<string, string>): Promise<void> {
  await json(await admin.put('/api/admin/content/settings', { data: { values, reason: '端到端测试：放宽每周文章、每天页面上限', checked: true } }), '改内容设置');
}

/** 等这一组草稿生成完（后台线程调替身） */
async function waitGroup(admin: APIRequestContext, draftNo: string): Promise<Draft[]> {
  let group: Draft[] = [];
  await expect
    .poll(
      async () => {
        group = await json<Draft[]>(await admin.get(`/api/admin/content/drafts/${draftNo}`), '草稿');
        return group.every((d) => d.status !== 'GENERATING');
      },
      { message: '等草稿生成完', timeout: 30_000, intervals: [500, 1000] },
    )
    .toBe(true);
  return group;
}

/** 高危确认弹窗：原因（已预填就不改）、勾确认、点"确认执行"，等这次操作的接口返回成功 */
async function confirmHighRisk(page: Page, urlPart: string): Promise<void> {
  const dlg = page.locator('.high-risk-modal');
  await expect(dlg).toBeVisible();
  await expect(page.locator('.ant-modal[class*="-appear"], .ant-modal[class*="-enter"]')).toHaveCount(0);
  const reason = dlg.getByRole('textbox');
  if (!(await reason.inputValue())) await reason.fill('端到端测试：核对过数字和措辞');
  await dlg.getByRole('checkbox').check();
  const done = page.waitForResponse((r) => r.url().includes(urlPart) && r.request().method() === 'POST', { timeout: 15_000 });
  await dlg.getByRole('button', { name: cjk('确认执行') }).click();
  const res = await done;
  expect(res.ok(), `${urlPart} 返回 ${res.status()}：${await res.text()}`).toBe(true);
  await expect(dlg).toBeHidden();
}

test('经济日历 → 市场草稿进"待上架"；文章草稿数字核对通过 → 发布 → SEO 页面；数字对不上不能发布', async ({ browser }) => {
  const anon = await request.newContext({ baseURL: STUB_URL });
  const actx = await browser.newContext({ baseURL: ADMIN_URL, ignoreHTTPSErrors: DEPLOYED, viewport: { width: 1366, height: 768 }, locale: 'zh-CN' });
  const page = await actx.newPage();
  const visitor = await browser.newContext({ baseURL: CLIENT_URL, ignoreHTTPSErrors: DEPLOYED, locale: 'zh-CN', serviceWorkers: 'block', viewport: { width: 390, height: 844 }, isMobile: true });
  const crawler = await browser.newContext({ baseURL: CLIENT_URL, ignoreHTTPSErrors: DEPLOYED, javaScriptEnabled: false, serviceWorkers: 'block' });
  let admin: APIRequestContext | undefined;
  let restore: Record<string, string> | undefined;
  try {
    await json(await anon.post('/api/e2e/clock/reset'), '时钟复原');
    await geminiMode(anon, { mode: 'ok', key: true });
    admin = await step('超管登录（接口）', () => adminSession(anon));
    const a = admin;
    const status = await json<{ settings: Record<string, string>; geminiConfigured: boolean }>(await a.get('/api/admin/content/status'), '内容中心状态');
    expect(status.geminiConfigured, 'e2e 配置的测试 key').toBe(true);
    restore = { 'articles.weekly': status.settings['articles.weekly'], 'pages.dailyNew': status.settings['pages.dailyNew'] };
    await setting(a, { 'articles.weekly': '20', 'pages.dailyNew': '50' });

    const econ = await step('经济日历：录入美国 CPI（3 天后公布，上月同比 3.0%）→ 跑一轮定时任务 → 生成市场草稿', async () => {
      const now = new Date((await json<{ now: string }>(await anon.get('/api/e2e/clock'), '读时钟')).now);
      const release = new Date(now.getTime() + 3 * 86_400_000 + Math.floor(Math.random() * 600) * 60_000);
      release.setUTCSeconds(0, 0);
      const year = 2030 + Math.floor(Math.random() * 60);
      const period = `${year}-${String(1 + Math.floor(Math.random() * 12)).padStart(2, '0')}`;
      const e = await json<Econ>(await a.post('/api/admin/content/econ', { data: { kind: 'CPI', period, releaseAt: release.toISOString(), prevValue: '3.0' } }), '录入经济日历');
      expect(e.status).toBe('SCHEDULED');
      await json(await anon.post('/api/e2e/content/tick'), '跑一轮内容定时任务');
      const after = (await json<Econ[]>(await a.get('/api/admin/content/econ'), '经济日历列表')).find((x) => x.id === e.id)!;
      expect(after.status, after.note ?? '').toBe('DRAFTED');
      expect(after.marketNo).toMatch(/^\d{15,19}$/);
      const m = await json<{ status: string; submitted: boolean; category: string; title: { zhCN: string }; createdBy: number | null }>(
        await a.get(`/api/admin/markets/${after.marketNo}`),
        '市场草稿',
      );
      expect(m).toMatchObject({ status: 'draft', submitted: true, category: 'MACRO', createdBy: null });
      expect(m.title.zhCN).toBe(`美国 ${year} 年 ${Number(period.slice(5))} 月 CPI 同比：高于、等于还是低于 3.0%？`);
      return { ...after, title: m.title.zhCN };
    });

    await step('后台登录（页面）', () => adminLogin(page));

    await step('后台"内容中心 → 事件候选"有这条和市场草稿编号；"市场管理 → 待上架"里有这个草稿（不自动上架）', async () => {
      await page.goto('/admin/content/events');
      await expect(page.getByRole('link', { name: econ.marketNo! })).toBeVisible();
      await shot(page, '01-admin-content-events');
      await page.goto('/admin/markets/review');
      await expect(page.getByText(econ.title)).toBeVisible();
    });

    const article = await step('草稿箱"生成草稿"选文章 → 替身起草中英文 → 检查通过（数字都在引用数据里）', async () => {
      await page.goto('/admin/content/drafts');
      await expect(page.getByTestId('gemini-banner')).toHaveCount(0);
      await page.getByTestId('ct-generate').click();
      const modal = page.locator('.ant-modal').filter({ hasText: '生成草稿' });
      await expect(modal).toBeVisible();
      await modal.locator('.ant-select').first().click();
      await page.locator('.ant-select-dropdown .ant-select-item-option').filter({ hasText: /^文章$/ }).click();
      await modal.getByLabel('主题').fill(`彩池玩法和费率 ${uniqueId()}`);
      const made = page.waitForResponse((r) => r.url().endsWith('/api/admin/content/drafts') && r.request().method() === 'POST');
      await modal.getByRole('button', { name: cjk('生成') }).click();
      const drafts = (await (await made).json()) as Draft[];
      expect(drafts.map((d) => d.lang).sort()).toEqual(['en', 'zh']);
      const group = await waitGroup(a, drafts[0].draftNo);
      for (const d of group) {
        expect(d.status, `${d.lang}：${d.checks?.summary ?? ''}`).toBe('PENDING');
        expect(d.checkOk).toBe(true);
      }
      // 抽屉每 2 秒刷新：两种语言都可以发布
      const zh = page.getByTestId('editor-zh');
      await expect(zh.getByTestId('publish-zh')).toBeEnabled({ timeout: 15_000 });
      await expect(zh.getByText(/检查通过/)).toBeVisible();
      await shot(page, '02-admin-content-draft-ok');
      return group;
    });

    const slug = await step('Amos 发布中文、英文（高危确认，写审计）→ 草稿变"已发布"，给出网站地址', async () => {
      for (const lang of ['zh', 'en'] as const) {
        await page.getByTestId(`publish-${lang}`).click();
        await confirmHighRisk(page, '/publish');
      }
      const group = await waitGroup(a, article[0].draftNo);
      for (const d of group) expect(d.status).toBe('PUBLISHED');
      const url = group.find((d) => d.siteUrl)?.siteUrl;
      expect(url).toMatch(/^\/insights\/article-[0-9a-z]+$/);
      await expect(page.getByTestId('editor-zh').getByRole('link', { name: '在网站上看' })).toBeVisible();
      return url!.split('/')[2];
    });

    await step('客户端 /insights 列表和文章页能打开（手机 390，不登录）', async () => {
      const p = await visitor.newPage();
      await p.goto('/insights');
      await expect(p.locator(`a[href="/insights/${slug}"]`)).toBeVisible();
      await p.goto(`/insights/${slug}`);
      const zh = article.find((d) => d.lang === 'zh')!;
      await expect(p.getByRole('heading', { name: zh.title })).toBeVisible();
      await expect(p.locator('.ins-body')).toContainText(zh.body.split('\n')[0].slice(0, 12));
      await expect(p.getByText('本页只陈述数据，不预测结果，也不构成任何建议。')).toBeVisible();
      const width = await p.evaluate(() => document.documentElement.scrollWidth);
      expect(width, '没有横向滚动').toBeLessThanOrEqual(390);
      await shot(p, '10-client-article-390');
      await p.goto('/insights');
      await shot(p, '11-client-insights-390');
      await p.close();
    });

    await step('搜索引擎看到的版本（关掉 JS）：标题、正文、规范地址；英文版是英文；站点地图里有这篇', async () => {
      const p = await crawler.newPage();
      const zh = article.find((d) => d.lang === 'zh')!;
      const en = article.find((d) => d.lang === 'en')!;
      const res = await p.goto(`/insights/${slug}?lang=zh-CN`);
      expect(res!.status()).toBe(200);
      let html = await p.content();
      expect(html).toContain(zh.title);
      expect(html).toMatch(new RegExp(`<link rel="canonical" href="[^"]*/insights/${slug}\\?lang=zh-CN"`));
      await p.goto(`/insights/${slug}?lang=en`);
      html = await p.content();
      expect(html).toContain(en.title);
      const missing = await p.goto('/insights/article-zzzzzzzz');
      expect(missing!.status(), '不存在的页面 404').toBe(404);
      const sm = await p.goto('/sitemap.xml');
      expect(await sm!.text()).toContain(`/insights/${slug}`);
      await p.close();
    });

    await step('数字对不上：替身多写一个引用数据里没有的数字 → 重写一次仍对不上 → 待审核但标红、不能发布；改掉后检查通过', async () => {
      await geminiMode(anon, { mode: 'wrongNumber' });
      const drafts = await json<Draft[]>(await a.post('/api/admin/content/drafts', { data: { kind: 'ARTICLE', topic: `费率和退款 ${uniqueId()}` } }), '生成文章草稿');
      const group = await waitGroup(a, drafts[0].draftNo);
      const zh = group.find((d) => d.lang === 'zh')!;
      expect(zh.status, '只是数字对不上：留给人改').toBe('PENDING');
      expect(zh.checkOk).toBe(false);
      expect(zh.checks!.numbers.map((n) => n.text)).toContain('987654');
      // 页面：标红、"发布"按钮不可用
      await page.goto('/admin/content/drafts');
      await page.getByTestId(`draft-${zh.draftNo}`).click();
      const ed = page.getByTestId('editor-zh');
      await expect(ed.locator('mark.ct-bad-number').filter({ hasText: '987654' }).first()).toBeVisible();
      await expect(ed.getByTestId('publish-zh')).toBeDisabled();
      await expect(ed.getByTestId('checks-zh')).toContainText('数字对不上引用数据');
      await shot(page, '03-admin-content-draft-red');
      // 接口：直接调发布也被拒（服务器发布前再检查一遍）
      const r = await a.post(`/api/admin/content/drafts/${zh.draftNo}/publish`, { data: { reason: '端到端测试：应该被拒', checked: true } });
      expect(r.status()).toBe(409);
      expect(((await r.json()) as { code: string }).code).toBe('DRAFT_CHECK_FAILED');
      // 页面上把这句删掉、保存：重新检查后通过，"发布"可以点了（不发布，驳回清理）
      const body = ed.getByLabel('正文 zh');
      await body.fill((await body.inputValue()).replace(/另有 987654 人在看。/, ''));
      await ed.getByTestId('save-zh').click();
      await expect(page.getByTestId('editor-zh').getByTestId('publish-zh')).toBeEnabled();
      await expect(page.getByTestId('editor-zh').locator('mark.ct-bad-number')).toHaveCount(0);
      for (const d of group) await json(await a.post(`/api/admin/content/drafts/${d.draftNo}/reject`, { data: { reason: '端到端测试：清理' } }), '驳回');
      await geminiMode(anon, { mode: 'ok' });
    });

    await step('其他内容中心页面能打开（截图）', async () => {
      for (const [path, name, text] of [
        ['/admin/content/drafts', '04-admin-content-drafts', '草稿箱'],
        ['/admin/content/pages', '05-admin-content-pages', 'BTC'],
        ['/admin/content/templates', '06-admin-content-templates', '美国 CPI'],
        ['/admin/content/tools', '07-admin-content-tools', '大 V 对照图'],
        ['/admin/content/logs', '08-admin-content-logs', '发布'],
        ['/admin/content/settings', '09-admin-content-settings', '已配置'],
      ] as const) {
        await page.goto(path);
        await expect(page.getByText(text).first()).toBeVisible();
        if (path.endsWith('/drafts')) await page.locator('.ant-segmented-item').filter({ hasText: /^全部$/ }).click();
        await shot(page, name);
      }
    });
  } finally {
    await geminiMode(anon, { mode: 'ok', key: true }).catch(() => undefined);
    if (admin && restore) await setting(admin, restore).catch(() => undefined);
    await admin?.dispose();
    await anon.dispose();
    await actx.close();
    await visitor.close();
    await crawler.close();
  }
});

test('没有 Gemini key：后台提示"未配置"、AI 起草不可用；经济日历草稿和 BTC 统计页照常', async ({ browser }) => {
  const anon = await request.newContext({ baseURL: STUB_URL });
  const actx = await browser.newContext({ baseURL: ADMIN_URL, ignoreHTTPSErrors: DEPLOYED, viewport: { width: 1366, height: 768 }, locale: 'zh-CN' });
  const page = await actx.newPage();
  const crawler = await browser.newContext({ baseURL: CLIENT_URL, ignoreHTTPSErrors: DEPLOYED, javaScriptEnabled: false, serviceWorkers: 'block' });
  let admin: APIRequestContext | undefined;
  let restore: Record<string, string> | undefined;
  try {
    await json(await anon.post('/api/e2e/clock/reset'), '时钟复原');
    await geminiMode(anon, { mode: 'ok', key: false });
    admin = await step('超管登录（接口）', () => adminSession(anon));
    const a = admin;
    const st = await json<{ settings: Record<string, string> }>(await a.get('/api/admin/content/status'), '内容中心状态');
    restore = { 'articles.weekly': st.settings['articles.weekly'] };
    await setting(a, { 'articles.weekly': '20' });
    const before = (await json<string[]>(await anon.get('/api/e2e/gemini/requests'), '替身收到的请求')).length;

    await step('状态是"未配置"；生成 AI 草稿返回"暂时用不了"，不调用 Gemini', async () => {
      const s = await json<{ geminiConfigured: boolean; geminiState: string }>(await a.get('/api/admin/content/status'), '状态');
      expect(s).toMatchObject({ geminiConfigured: false, geminiState: 'unconfigured' });
      const r = await a.post('/api/admin/content/drafts', { data: { kind: 'ARTICLE', topic: `没有 key ${uniqueId()}` } });
      expect(r.status()).toBe(409);
      expect(((await r.json()) as { code: string }).code).toBe('AI_UNAVAILABLE');
    });

    await step('经济日历照常生成市场草稿（模板，不用 AI）', async () => {
      const now = new Date((await json<{ now: string }>(await anon.get('/api/e2e/clock'), '读时钟')).now);
      const release = new Date(now.getTime() + 2 * 86_400_000 + Math.floor(Math.random() * 600) * 60_000);
      release.setUTCSeconds(0, 0);
      const period = `${2030 + Math.floor(Math.random() * 60)}-${String(1 + Math.floor(Math.random() * 12)).padStart(2, '0')}`;
      const e = await json<Econ>(await a.post('/api/admin/content/econ', { data: { kind: 'FOMC', period, releaseAt: release.toISOString(), prevValue: '4.50' } }), '录入 FOMC');
      const d = await json<Econ>(await a.post(`/api/admin/content/econ/${e.id}/draft`), '立即生成');
      expect(d.status, d.note ?? '').toBe('DRAFTED');
      expect(d.marketNo).toBeTruthy();
    });

    await step('BTC 统计页照常刷新，搜索引擎版本能打开', async () => {
      await json(await a.post('/api/admin/content/pages/btc-hourly/refresh'), '刷新 BTC 统计');
      const p = await crawler.newPage();
      const res = await p.goto('/insights/btc-hourly?lang=zh-CN');
      expect(res!.status()).toBe(200);
      expect(await p.content()).toContain('BTC');
      await p.close();
    });

    await step('后台草稿箱顶部提示"Gemini 未配置"，说明其他照常', async () => {
      await adminLogin(page);
      await page.goto('/admin/content/drafts');
      const banner = page.getByTestId('gemini-banner');
      await expect(banner).toContainText('Gemini 未配置');
      await expect(banner).toContainText('市场草稿、数据卡、页面数据照常更新');
      await shot(page, '12-admin-content-no-key');
    });

    const after = (await json<string[]>(await anon.get('/api/e2e/gemini/requests'), '替身收到的请求')).length;
    expect(after, '没有 key 时一次也没调用').toBe(before);
  } finally {
    await geminiMode(anon, { mode: 'ok', key: true }).catch(() => undefined);
    if (admin && restore) await setting(admin, restore).catch(() => undefined);
    await admin?.dispose();
    await anon.dispose();
    await actx.close();
    await crawler.close();
  }
});
