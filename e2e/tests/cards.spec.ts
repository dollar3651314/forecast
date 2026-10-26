/**
 * 分享卡片（forecast S14，MF-5；需求说明书 v1.3.2 第 5.2 节"分享卡片和挑战卡"、AC-86、AC-87；开发记录/S14-分享卡片.md）。两种形态都跑（tests/targets.ts）。
 * 1. 电脑 1366×768：市场详情"分享"（市场卡，链接带 src=share、cmp=card-market、分享人的随机分享码 ref——S15 起不再是客户编号）→ 下注 → 弹出挑战卡（主操作在首屏，C79）→
 *    不跑 JS 打开挑战卡链接（Telegram、X 的抓取看到的）：og:image 是这张挑战卡、twitter:card 大图；图片是 1200×630 的 PNG（经过 Nginx）→
 *    带 JS 打开链接：记一次 src=share 的访问 → 后台来源统计里分享码换算成这个分享人的客户编号（MF-1，不发任何奖励）。
 * 2. 手机 390 / 375：市场卡"分享"调系统分享面板（navigator.share），下注后挑战卡的"分享"按钮在首屏内（C79）。
 * 3. 平台裁决结算后：赢家在"我的下注"分享押中的开奖卡，派彩金额默认不显示、勾选后显示（AC-86）；取消的市场、周报、封盘倒计时的卡片图片；robots 放行卡片图片。
 */
import { expect, request, test, type APIRequestContext, type Page } from '@playwright/test';
import { adminSession, bet, clientSession, fund, json, listMarket } from './api';
import { CLIENT_PASSWORD, step, uniqueId } from './support';
import { CLIENT_URL, DEPLOYED, STUB_URL } from './targets';

type Share = { kind: string; url: string; imageUrl: string; title: string; text: string };

/** 分享码（S15，后端 ShareCodeApi.FORMAT）：10 位小写字母和数字，去掉容易看错的 i、l、o、0、1 */
const CODE = '[a-hj-km-np-z][a-hj-km-np-z2-9]{9}';

/** PNG 的宽高（IHDR：第 16～23 字节） */
function pngSize(b: Buffer): { w: number; h: number } {
  expect(b.subarray(1, 4).toString('latin1'), 'PNG 文件头').toBe('PNG');
  return { w: b.readUInt32BE(16), h: b.readUInt32BE(20) };
}

/** 在浏览器里打开图片地址（部署形态经过 Nginx 和假域名），检查是 1200×630 的 PNG */
async function expectCardImage(page: Page, url: string): Promise<void> {
  const res = await page.goto(url);
  expect(res, url).not.toBeNull();
  expect(res!.status(), url).toBe(200);
  expect(res!.headers()['content-type']).toContain('image/png');
  expect(pngSize(await res!.body())).toEqual({ w: 1200, h: 630 });
}

async function loginInPage(page: Page, email: string): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('邮箱', { exact: true }).fill(email);
  await page.getByLabel('密码', { exact: true }).fill(CLIENT_PASSWORD);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page).not.toHaveURL(/\/login$/);
  // 新客户第一次登录会弹新手引导（不一定马上出现）：出现了就跳过
  const guide = page.getByRole('dialog').filter({ hasText: '新手引导' });
  const shown = await guide.waitFor({ state: 'visible', timeout: 5_000 }).then(() => true, () => false);
  if (shown) await guide.getByRole('button', { name: '跳过', exact: true }).click();
}

/** 元素的底边在视口里（C79：不滚动就能看到） */
async function expectInFirstScreen(page: Page, testId: string, where: string): Promise<void> {
  const box = await page.getByTestId(testId).boundingBox();
  const vp = page.viewportSize()!;
  expect(box, where).not.toBeNull();
  expect(box!.y + box!.height, `${where}：底边 ${Math.round(box!.y + box!.height)} ≤ 视口 ${vp.height}`).toBeLessThanOrEqual(vp.height);
}

async function userIdOf(api: APIRequestContext): Promise<string> {
  return (await json<{ userId: string }>(await api.get('/api/me/profile'), '资料')).userId;
}

test('电脑：市场卡分享带分享人 → 下注后挑战卡 → 链接预览是这张挑战卡 → 打开链接记来源', async ({ browser }) => {
  const anon = await request.newContext({ baseURL: STUB_URL });
  const ctx = await browser.newContext({ baseURL: CLIENT_URL, ignoreHTTPSErrors: DEPLOYED, viewport: { width: 1366, height: 768 }, locale: 'zh-CN', serviceWorkers: 'block' });
  const page = await ctx.newPage();
  const crawler = await browser.newContext({ baseURL: CLIENT_URL, ignoreHTTPSErrors: DEPLOYED, javaScriptEnabled: false, serviceWorkers: 'block' });
  const visitor = await browser.newContext({ baseURL: CLIENT_URL, ignoreHTTPSErrors: DEPLOYED, locale: 'zh-CN', serviceWorkers: 'block' });
  try {
    await json(await anon.post('/api/e2e/clock/reset'), '时钟复原');
    const admin = await step('超管登录（接口）', () => adminSession(anon));
    const title = `分享卡片 ${uniqueId()}`;
    const m = await step('后台上架一个市场', () => listMarket(anon, admin, title));
    const client = await step('注册客户并充值 100', async () => {
      const c = await clientSession(anon, 'card');
      await fund(anon, c.api, '100');
      return c;
    }, 120_000);
    const uid = await userIdOf(client.api);
    await step('登录（页面）', () => loginInPage(page, client.email));

    const code = await step('市场详情"分享"：市场卡图片、链接带 src=share、cmp=card-market、ref=分享码（不是客户编号）、cl=zh-CN', async () => {
      await page.goto(`/markets/${m.no}`);
      const loaded = page.waitForResponse((r) => r.url().includes(`/api/public/cards/market/${m.no}.png`));
      await page.getByTestId('share-open').click();
      const dlg = page.getByTestId('share-dialog');
      await expect(dlg).toBeVisible();
      expect((await loaded).status()).toBe(200);
      await expect(dlg.getByTestId('share-img')).toHaveJSProperty('naturalWidth', 1200);
      await expect(dlg.getByTestId('share-url')).toHaveValue(new RegExp(`/m/${m.no}\\?src=share&cmp=card-market&ref=${CODE}&cl=zh-CN$`));
      const url = await dlg.getByTestId('share-url').inputValue();
      const c = /[?&]ref=([^&]+)/.exec(url)![1];
      expect(c, '链接里不出现客户编号').not.toBe(uid);
      await expectInFirstScreen(page, 'share-copy', '电脑 1366×768 分享弹窗的"复制链接"');
      await page.keyboard.press('Escape');
      await expect(dlg).toHaveCount(0);
      return c;
    });

    const link = await step('右栏押"是" 10 → 下注成功后弹出挑战卡（可以关掉）', async () => {
      const panel = page.getByTestId('bet-panel');
      await panel.getByLabel('金额（USDT）').fill('10');
      await panel.getByTestId('bet-confirm').click();
      const dlg = page.getByRole('dialog', { name: '下注成功 · 挑战卡' });
      await expect(dlg).toBeVisible();
      await expect(dlg.getByTestId('share-img')).toHaveJSProperty('naturalWidth', 1200);
      const url = await dlg.getByTestId('share-url').inputValue();
      expect(url).toMatch(new RegExp(`/m/${m.no}\\?src=share&cmp=card-challenge&ref=${code}&cl=zh-CN&card=[A-Za-z0-9_-]{22}$`));
      await expectInFirstScreen(page, 'share-copy', '电脑 1366×768 挑战卡的"复制链接"');
      await expect(dlg.getByTestId('share-tg')).toHaveAttribute('href', new RegExp(`^https://t\\.me/share/url\\?url=${encodeURIComponent(url).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`));
      await dlg.getByRole('button', { name: '关闭' }).click();
      await expect(dlg).toHaveCount(0);
      return url;
    });

    await step('不跑 JS 打开挑战卡链接（链接预览抓取看到的）：og:image 是这张挑战卡、大图卡片、中文标题；图片是 1200×630 PNG', async () => {
      const p = await crawler.newPage();
      const res = await p.goto(link);
      expect(res!.status()).toBe(200);
      expect(p.url()).toContain(`/markets/${m.no}?`);
      const html = await p.content();
      const token = link.slice(link.indexOf('card=') + 5);
      const og = /<meta property="og:image" content="([^"]+)"/.exec(html)?.[1]?.replace(/&amp;/g, '&');
      expect(og).toBeTruthy();
      expect(og).toContain(`/api/public/cards/s/${token}.png?lang=zh-CN`);
      expect(html).toContain('<meta name="twitter:card" content="summary_large_image">');
      expect(html).toContain('<meta property="og:image:width" content="1200">');
      expect(html).toMatch(/<meta name="twitter:title" content="我押了「是（简）」，谁敢押「否（简）」？/);
      await expectCardImage(p, og!);
      await p.close();
    });

    await step('带 JS 打开链接：记一次带来源的访问（src=share、cmp=card-challenge、ref=分享码）', async () => {
      const p = await visitor.newPage();
      const visit = p.waitForRequest((r) => r.url().includes('/api/attribution/visit') && r.method() === 'POST');
      await p.goto(link);
      const body = (await visit).postDataJSON() as { src?: string; cmp?: string; ref?: string };
      expect(body).toMatchObject({ src: 'share', cmp: 'card-challenge', ref: code });
      await expect(p).toHaveURL(new RegExp(`/markets/${m.no}\\?`));
      await expect(p.getByRole('heading', { name: new RegExp(title) })).toBeVisible();
      await p.close();
    });

    await step('后台来源统计：这个分享码有 1 次访问，换算成分享人的客户编号（只记来源，不发奖励）', async () => {
      const a = await adminSession(anon);
      const r = await json<{ cohorts: { src: string; cmp: string; ref: string; refCustomer: string | null; visits: number }[] }>(await a.get('/api/admin/growth/report'), '来源统计');
      const row = r.cohorts.find((c) => c.src === 'share' && c.cmp === 'card-challenge' && c.ref === code);
      expect(row?.visits).toBe(1);
      expect(row?.refCustomer).toBe(uid);
      await a.dispose();
    });
    await client.api.dispose();
    await admin.dispose();
  } finally {
    await ctx.close();
    await crawler.close();
    await visitor.close();
    await anon.dispose();
  }
});

for (const vp of [
  { width: 390, height: 844 },
  { width: 375, height: 667 },
]) {
  test(`手机 ${vp.width}×${vp.height}：市场卡"分享"调系统分享面板；下注后挑战卡的"分享"在首屏内`, async ({ browser }) => {
    const anon = await request.newContext({ baseURL: STUB_URL });
    const ctx = await browser.newContext({ baseURL: CLIENT_URL, ignoreHTTPSErrors: DEPLOYED, viewport: vp, isMobile: true, hasTouch: true, locale: 'zh-CN', serviceWorkers: 'block' });
    // 系统分享面板：Chromium 无头模式没有，这里换成一个记下参数的函数（和真机上一样由页面在点击时调用）
    await ctx.addInitScript(() => {
      const w = window as unknown as { __shared: ShareData[] };
      w.__shared = [];
      Object.defineProperty(navigator, 'share', { configurable: true, value: async (d: ShareData) => void w.__shared.push(d) });
    });
    const page = await ctx.newPage();
    try {
      await json(await anon.post('/api/e2e/clock/reset'), '时钟复原');
      const admin = await step('超管登录、上架市场', () => adminSession(anon));
      const m = await step('上架一个市场', () => listMarket(anon, admin, `手机分享 ${uniqueId()}`));
      const client = await step('注册客户并充值 50', async () => {
        const c = await clientSession(anon, 'mcard');
        await fund(anon, c.api, '50');
        return c;
      }, 120_000);
      await step('登录（页面）', () => loginInPage(page, client.email));
      await step('市场详情"分享" → "分享"按钮调系统分享面板（标题、卡片文字、带 src=share 的链接）', async () => {
        await page.goto(`/markets/${m.no}`);
        await page.getByTestId('share-open').click();
        await expect(page.getByTestId('share-img')).toHaveJSProperty('naturalWidth', 1200);
        await expectInFirstScreen(page, 'share-native', `手机 ${vp.width}×${vp.height} 分享弹窗的"分享"`);
        await page.getByTestId('share-native').click();
        const shared = await page.evaluate(() => (window as unknown as { __shared: ShareData[] }).__shared);
        expect(shared).toHaveLength(1);
        expect(shared[0].url).toMatch(new RegExp(`/m/${m.no}\\?src=share&cmp=card-market&ref=${CODE}&cl=zh-CN$`));
        expect(shared[0].text).toContain('奖池');
        await page.getByRole('button', { name: '关闭' }).click();
      });
      await step('押"否" 10 → 抽屉关闭、弹出挑战卡，"分享"在首屏内', async () => {
        await page.getByRole('button', { name: '押注', exact: true }).nth(1).click();
        const sheet = page.getByRole('dialog');
        await sheet.getByLabel('金额（USDT）').fill('10');
        await sheet.getByTestId('bet-confirm').click();
        const dlg = page.getByRole('dialog', { name: '下注成功 · 挑战卡' });
        await expect(dlg).toBeVisible();
        await expect(dlg.getByTestId('share-img')).toHaveJSProperty('naturalWidth', 1200);
        await expectInFirstScreen(page, 'share-native', `手机 ${vp.width}×${vp.height} 挑战卡的"分享"`);
        await dlg.getByTestId('share-native').click();
        const shared = await page.evaluate(() => (window as unknown as { __shared: ShareData[] }).__shared);
        expect(shared.at(-1)!.text).toContain('我押了「否（简）」，谁敢押「是（简）」？');
        expect(shared.at(-1)!.text).toContain('对面目前 0 人；截止时仍只有一边有人押，全额退款');
      });
      await client.api.dispose();
      await admin.dispose();
    } finally {
      await ctx.close();
      await anon.dispose();
    }
  });
}

test('开奖卡：赢家分享押中的开奖卡，金额默认不显示、勾选后显示；取消的市场、周报、封盘倒计时的卡片；robots 放行卡片图片', async ({ browser }) => {
  const anon = await request.newContext({ baseURL: STUB_URL });
  const ctx = await browser.newContext({ baseURL: CLIENT_URL, ignoreHTTPSErrors: DEPLOYED, viewport: { width: 1366, height: 768 }, locale: 'zh-CN', serviceWorkers: 'block' });
  const page = await ctx.newPage();
  try {
    await json(await anon.post('/api/e2e/clock/reset'), '时钟复原');
    let admin = await step('超管登录', () => adminSession(anon));
    const winner = await step('注册客户 A（押"是"）', async () => {
      const c = await clientSession(anon, 'cwin');
      await fund(anon, c.api, '100');
      return c;
    }, 120_000);
    const loser = await step('注册客户 B（押"否"）', async () => {
      const c = await clientSession(anon, 'close');
      await fund(anon, c.api, '100');
      return c;
    }, 120_000);
    const m = await step('上架两个市场：一个结算、一个取消；再上架一个看封盘倒计时卡', async () => {
      const settled = await listMarket(anon, admin, `开奖卡 ${uniqueId()}`);
      const cancelled = await listMarket(anon, admin, `取消卡 ${uniqueId()}`);
      const countdown = await listMarket(anon, admin, `倒计时卡 ${uniqueId()}`);
      await bet(winner.api, settled.no, settled.yes, '40');
      await bet(loser.api, settled.no, settled.nope, '60');
      await bet(winner.api, cancelled.no, cancelled.yes, '10');
      await bet(loser.api, countdown.no, countdown.yes, '15');
      return { settled, cancelled, countdown };
    });
    await step('封盘倒计时卡（截止前 2 分钟）：距截止几分钟、"否"目前 0 人照实写；图片 1200×630', async () => {
      const s = await json<Share>(await anon.get(`/api/public/share?kind=countdown&no=${m.countdown.no}&lang=zh-CN`), '倒计时卡');
      expect(s.kind).toBe('countdown');
      expect(s.text).toMatch(/^距截止 [12] 分钟/);
      expect(s.text).toContain('「否（简）」目前 0 人；截止时仍只有一边有人押，全额退款');
      await expectCardImage(page, s.imageUrl);
    });
    await step('取消一个市场：卡片换成开奖卡（已取消、原因、全额退款）', async () => {
      await json(await admin.post(`/api/admin/markets/${m.cancelled.no}/cancel`, { data: { reason: '端到端取消' } }), '取消');
      await anon.post('/api/e2e/settle/tick');
      await expect
        .poll(async () => (await json<Share>(await anon.get(`/api/public/share?kind=market&no=${m.cancelled.no}&lang=zh-CN`), '取消卡')).text, { timeout: 20_000 })
        .toContain('已全额退款 10.00 USDT');
      const s = await json<Share>(await anon.get(`/api/public/share?kind=market&no=${m.cancelled.no}&lang=en`), '取消卡（英文）');
      expect(s.kind).toBe('result');
      expect(s.text).toMatch(/^Cancelled/);
      await expectCardImage(page, s.imageUrl);
    });
    await step('结算一个市场：截止 → 事件结束 → 裁决台提议"是"、确认 → 争议窗口到期 → 已结算', async () => {
      await json(await anon.post('/api/e2e/clock/advance', { data: { millis: 120_000 } }), '拨到截止');
      await json(await anon.post('/api/e2e/markets/tick'), '截止任务');
      await json(await anon.post('/api/e2e/clock/advance', { data: { millis: 60_000 } }), '拨到事件结束');
      await json(await anon.post('/api/e2e/markets/tick'), '事件结束任务');
      admin = await adminSession(anon);
      const proposed = await json<{ detail: { summary: { proposal: { proposalId: string } } } }>(
        await admin.post(`/api/admin/resolve/cases/${m.settled.no}/proposals`, { data: { optionNo: m.settled.yes, evidenceUrls: ['https://example.org/official'], note: '官方公布：是' } }),
        '提议',
      );
      const id = proposed.detail.summary.proposal.proposalId;
      await json(await admin.post(`/api/admin/resolve/cases/${m.settled.no}/proposals/${id}/confirm`, { data: { checked: true, reason: '核对官网' } }), '确认');
      await json(await anon.post('/api/e2e/clock/advance', { data: { millis: 120_000 } }), '拨到争议截止');
      await json(await anon.post('/api/e2e/resolve/tick'), '公示期满结算');
      await anon.post('/api/e2e/settle/tick');
      await expect.poll(async () => (await json<{ status: string }>(await winner.api.get(`/api/markets/${m.settled.no}`), '详情')).status, { timeout: 20_000 }).toBe('settled');
    });
    await step('开奖卡（公开）：结果、奖池、派彩合计、抽成、裁决人编号', async () => {
      const s = await json<Share>(await anon.get(`/api/public/share?kind=market&no=${m.settled.no}&lang=zh-CN`), '开奖卡');
      expect(s.kind).toBe('result');
      // T = 100，赢方 40：盈利 60，抽成 3，派彩 97
      expect(s.text).toContain('结果：是（简）');
      expect(s.text).toContain('奖池 100.00 USDT · 派彩合计 97.00 USDT · 抽成 3.00 USDT · 尾差 0.00 USDT');
      expect(s.text).toContain('平台裁决 · 裁决人编号');
      await expectCardImage(page, s.imageUrl);
    });
    await step('赢家在"我的下注 → 已结算"分享押中的开奖卡：金额默认不显示；勾选后显示我的派彩', async () => {
      await json(await anon.post('/api/e2e/clock/reset'), '时钟复原（页面登录）');
      await loginInPage(page, winner.email);
      await page.goto('/bets?tab=settled');
      await page.getByTestId('share-win').first().click();
      const dlg = page.getByTestId('share-dialog');
      await expect(dlg.getByTestId('share-amount')).not.toBeChecked();
      await expect(dlg.getByTestId('share-img')).toHaveJSProperty('naturalWidth', 1200);
      const hidden = await dlg.getByTestId('share-url').inputValue();
      expect(hidden).toContain('cmp=card-win');
      const plain = await json<Share>(await winner.api.post('/api/me/share', { data: { kind: 'win', marketNo: m.settled.no, lang: 'zh-CN' } }), '开奖卡（不显示金额）');
      expect(plain.text).toContain('我押中了「是（简）」');
      expect(plain.text).not.toContain('我的派彩');
      await dlg.getByTestId('share-amount').check();
      await expect(dlg.getByTestId('share-url')).not.toHaveValue(hidden);
      await expect(dlg.getByTestId('share-img')).toHaveJSProperty('naturalWidth', 1200);
      const shown = await json<Share>(await winner.api.post('/api/me/share', { data: { kind: 'win', marketNo: m.settled.no, showAmount: true, lang: 'zh-CN' } }), '开奖卡（显示金额）');
      expect(shown.text).toContain('我的派彩 97.00 USDT（已扣抽成）');
      // 没押中的不能生成押中的开奖卡
      expect((await loser.api.post('/api/me/share', { data: { kind: 'win', marketNo: m.settled.no } })).status()).toBe(400);
    });
    await step('周报卡（本周）：图片 1200×630，周报页的链接预览是周报卡', async () => {
      const weeks = await json<{ items: { week: string }[] }>(await anon.get('/api/public/weekly'), '周报列表');
      const week = weeks.items[0].week;
      const s = await json<Share>(await anon.get(`/api/public/share?kind=weekly&week=${week}&lang=zh-TW`), '周报卡');
      expect(s.text).toContain(`${week} 週報`);
      await expectCardImage(page, s.imageUrl);
      const crawler = await browser.newContext({ baseURL: CLIENT_URL, ignoreHTTPSErrors: DEPLOYED, javaScriptEnabled: false, serviceWorkers: 'block' });
      const p = await crawler.newPage();
      await p.goto(`/results/weekly/${week}`);
      expect(await p.content()).toContain(`/api/public/cards/weekly/${week}.png?lang=en`);
      const robots = await p.goto('/robots.txt');
      const text = await robots!.text();
      expect(text.indexOf('Allow: /api/public/cards/'), 'robots 放行卡片图片（在 Disallow: /api/ 前面）').toBeGreaterThanOrEqual(0);
      expect(text.indexOf('Allow: /api/public/cards/')).toBeLessThan(text.indexOf('Disallow: /api/'));
      await crawler.close();
    });
    await winner.api.dispose();
    await loser.api.dispose();
    await admin.dispose();
  } finally {
    await anon.post('/api/e2e/clock/reset');
    await ctx.close();
    await anon.dispose();
  }
});
