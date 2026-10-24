/**
 * 官网：两种运行形态都跑（默认经 server.mjs 的 4174 端口；部署形态经 Nginx 的官网假域名）。
 * 检查：区块、注册登录跳转带语言、中英文和默认语言跟随浏览器、手机和电脑两套结构且没有横向滚动、没有 Google 请求、法律页、测试环境标识。
 * forecast S1 删除了复制源的公开行情（行情面板、/api/public/quotes），对应的检查一起删除。
 */
import { expect, test, type Browser, type Page } from '@playwright/test';
import { BRAND, expectNoHorizontalScroll, Recorder, step } from './support';
import { CLIENT_URL, EXPECT_ENV_BANNER, SITE_URL } from './targets';

const CJK = /[\u4e00-\u9fff]/;

async function open(browser: Browser, width: number, locale: string, path = '/'): Promise<{ page: Page; rec: Recorder; close: () => Promise<void> }> {
  const ctx = await browser.newContext({ viewport: { width, height: width < 1024 ? 844 : 900 }, locale, ignoreHTTPSErrors: true, serviceWorkers: 'block' });
  const page = await ctx.newPage();
  const rec = new Recorder(page);
  await page.goto(`${SITE_URL}${path}`);
  return { page, rec, close: () => ctx.close() };
}

test('官网：区块、跳转、中英文、三种宽度、法律页', async ({ browser }) => {
  // ---------- 手机 390，浏览器语言中文 ----------
  const m = await open(browser, 390, 'zh-CN');
  const page = m.page;

  await step('手机：默认中文（跟随浏览器），区块都在，手机结构', async () => {
    await expect(page.locator('html')).toHaveAttribute('lang', 'zh-CN');
    await expect(page.locator('.site')).toHaveAttribute('data-layout', 'mobile');
    for (const id of ['risk-bar', 'logo', 'hero', 'sec-platform', 'sec-steps', 'sec-safety', 'sec-faq', 'footer', 'sticky-cta']) {
      await expect(page.getByTestId(id).first(), `区块 ${id}`).toBeVisible();
    }
    // 手机上导航收进菜单，注册按钮始终可见（底部固定）
    await expect(page.getByTestId('menu-btn')).toBeVisible();
    await expect(page.getByTestId('nav-register')).toHaveCount(0);
    await page.mouse.wheel(0, 2000);
    await expect(page.getByTestId('sticky-register')).toBeInViewport();
    // 联系邮箱跟域名走，还没定：显示"待提供"（web/site/src/lib/brand.ts）
    await expect(page.getByTestId('contact')).toContainText('待提供');
    await expect(page.getByTestId('footer')).toContainText(`© ${BRAND}`);
    await expect(page.getByTestId('quote-board'), '没有公开行情').toHaveCount(0);
    if (EXPECT_ENV_BANNER) await expect(page.getByTestId('env-banner')).toBeVisible();
    await expectNoHorizontalScroll(page, '官网首页 390');
  });

  await step('官网只调健康检查一个接口；没有 Google 和其他外部请求', async () => {
    await m.rec.settle();
    const paths = new Set(m.rec.apiBodies.map((b) => new URL(b.url).pathname));
    expect([...paths].filter((p) => p !== '/api/health'), '官网只调健康检查').toEqual([]);
    expect(m.rec.wsFrames, '官网不用 WebSocket').toEqual([]);
    expect(m.rec.riskLeaks()).toEqual([]);
    expect(m.rec.googleHosts()).toEqual([]);
    expect([...m.rec.hosts], '只访问官网自己的域名').toEqual([new URL(SITE_URL).hostname]);
  });

  await step('法律页：三个都能打开，标"需法务审核"，风险提示条和页脚风险提示都在', async () => {
    for (const [p, title] of [['/terms', '服务条款'], ['/privacy', '隐私政策'], ['/risk', '风险披露']]) {
      await page.goto(`${SITE_URL}${p}`);
      await expect(page.getByTestId('legal-review')).toContainText('需法务审核');
      await expect(page.getByRole('heading', { level: 1 })).toHaveText(title);
      await expect(page.getByTestId('risk-bar')).toBeVisible();
      await expect(page.getByTestId('footer-risk')).toContainText('风险提示');
      await expectNoHorizontalScroll(page, `官网 ${p} 390`);
    }
  });

  await step('手机：首屏"免费注册"跳到客户端注册页，语言是中文', async () => {
    await page.goto(`${SITE_URL}/`);
    await page.getByTestId('hero-register').click();
    await expect(page).toHaveURL(`${CLIENT_URL}/register?lang=zh-CN`);
    await expect(page.locator('html')).toHaveAttribute('lang', 'zh-CN');
  });
  await m.close();

  // ---------- 电脑 1280 / 1440，浏览器语言英文 ----------
  for (const width of [1280, 1440]) {
    const d = await open(browser, width, 'en-US');
    await step(`电脑 ${width}：默认英文（浏览器不是中文），电脑结构，没有中文残留，没有横向滚动`, async () => {
      await expect(d.page.locator('html')).toHaveAttribute('lang', 'en');
      await expect(d.page.locator('.site')).toHaveAttribute('data-layout', 'desktop');
      await expect(d.page.getByTestId('nav-register')).toBeVisible();
      await expect(d.page.getByTestId('sticky-cta')).toHaveCount(0);
      await expect(d.page.getByTestId('hero-note')).toBeVisible();
      const text = await d.page.locator('body').innerText();
      expect(text.match(CJK), '英文页面的中文残留（语言下拉框收起时只显示 EN）').toBeNull();
      // 语言下拉框显示当前语言
      await expect(d.page.getByTestId('lang-switch').getByRole('button', { name: 'Language: English' })).toBeVisible();
      await expectNoHorizontalScroll(d.page, `官网首页 ${width}`);
    });
    if (width === 1440) {
      await step('切换中文后显示中文，刷新后仍是中文（记住选择）', async () => {
        const sw = d.page.getByTestId('lang-switch');
        await sw.getByRole('button', { name: 'Language: English' }).click();
        await expect(sw.getByRole('option', { name: /English/ }), '当前语言打勾').toHaveAttribute('aria-selected', 'true');
        await sw.getByRole('option', { name: /简体中文/ }).getByRole('button').click();
        await expect(d.page.locator('html')).toHaveAttribute('lang', 'zh-CN');
        await expect(d.page.getByTestId('hero')).toContainText('就用 USDT 预测');
        await d.page.reload();
        await expect(d.page.locator('html')).toHaveAttribute('lang', 'zh-CN');
        await sw.getByRole('button', { name: '语言: 简体中文' }).click();
        await sw.getByRole('option', { name: /English/ }).getByRole('button').click();
        await expect(d.page.locator('html')).toHaveAttribute('lang', 'en');
      });
      await step('电脑：顶部"注册"跳到客户端注册页，"登录"跳到登录页，语言是英文', async () => {
        await expect(d.page.getByTestId('nav-login')).toHaveAttribute('href', `${CLIENT_URL}/login?lang=en`);
        await d.page.getByTestId('nav-register').click();
        await expect(d.page).toHaveURL(`${CLIENT_URL}/register?lang=en`);
        await expect(d.page.locator('html')).toHaveAttribute('lang', 'en');
      });
    }
    await d.close();
  }

  // ---------- 客户端也默认跟随浏览器语言（不经过官网、没有 ?lang=）----------
  for (const [locale, lang, label] of [['en-US', 'en', 'Language：English'], ['zh-TW', 'zh-CN', '语言：简体中文'], ['ja-JP', 'en', 'Language：English']] as const) {
    await step(`客户端登录页：浏览器语言 ${locale} → ${lang}，语言下拉框显示当前语言`, async () => {
      const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 }, locale, ignoreHTTPSErrors: true, serviceWorkers: 'block' });
      try {
        const p = await ctx.newPage();
        await p.goto(`${CLIENT_URL}/login`);
        await expect(p.locator('html')).toHaveAttribute('lang', lang);
        await expect(p.getByRole('button', { name: label })).toBeVisible();
      } finally {
        await ctx.close();
      }
    });
  }
});
