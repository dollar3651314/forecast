/**
 * 客户端页面的关键用例（forecast S11；需求第 5 节 P1～P10、AC-17、AC-50、AC-52、第 7 节 #23；开发记录/S11-客户端.md）。
 * 1. 手机 390×844：注册（勾选"已年满 18 周岁并同意条款"）→ 充值到账 → 市场列表点选项 → 下注抽屉押注（确认按钮在首屏内，C79）→
 *    另一个客户押对面，详情页的奖池 2 秒内自动更新（WS 推送，AC-17）→ 截止、平台裁决、公示期满结算 → 我的下注看到"押中"和派彩、
 *    详情看到结果和结算合计、通知中心点结算通知回到这个市场。
 * 2. 电脑 1366×768：开赛提醒（#23，SQ-20）：注册后自动登录的不能订（提示要用邮箱和密码登录过）→ 退出再登录 → 在还没开放的市场订阅 →
 *    "通知偏好"里看到、退订另一个 → 到开放时间（可调时钟）发出一封邮件（链接完整），退订的不发 → 邮件里的退订链接不用登录也能打开。
 * 两种形态都跑（tests/targets.ts）。拨过的时钟在结束时复原。
 */
import { expect, request, test, type APIRequestContext, type BrowserContext, type Page } from '@playwright/test';
import { adminSession, bet, clientSession, fund, json, listMarket, t, withCookie } from './api';
import { CLIENT_PASSWORD, e2e, expectNoHorizontalScroll, step, uniqueId } from './support';
import { CLIENT_URL, DEPLOYED, STUB_URL } from './targets';

/** 在页面里注册（和主流程同样的填法），返回邮箱；注册后会自动登录、弹出新手引导（这里跳过） */
async function registerInPage(page: Page, anon: APIRequestContext, prefix: string): Promise<string> {
  const email = `${prefix}-${uniqueId()}@example.com`;
  await page.goto('/register');
  await page.getByLabel('邮箱', { exact: true }).fill(email);
  const sentAt = new Date(Date.now() - 1000).toISOString();
  await page.getByRole('button', { name: '发送验证码' }).click();
  const code = await e2e.mailCode(anon, email, sentAt);
  await page.getByLabel('邮箱验证码').fill(code);
  await page.getByLabel('密码', { exact: true }).fill(CLIENT_PASSWORD);
  await page.getByLabel('确认密码').fill(CLIENT_PASSWORD);
  await page.getByLabel('名', { exact: true }).fill('测试');
  await page.getByLabel('姓', { exact: true }).fill('客户端');
  const birth = page.getByRole('group', { name: '出生日期' });
  await birth.getByLabel('年', { exact: true }).fill('1990');
  await birth.getByLabel('月', { exact: true }).fill('01');
  await birth.getByLabel('日', { exact: true }).fill('27');
  await page.getByLabel('国籍').click();
  await page.getByRole('textbox', { name: '搜索' }).fill('SG');
  await page.getByRole('option').first().click();
  // 没勾"已年满 18 周岁并同意条款"不能注册（需求 P8）
  await page.getByRole('button', { name: '注册', exact: true }).click();
  await expect(page.getByText('请阅读并同意条款')).toBeVisible();
  const box = page.getByRole('checkbox');
  await expect(page.getByText(/我已年满 18 周岁/)).toBeVisible();
  await box.check();
  await page.getByRole('button', { name: '注册', exact: true }).click();
  await expect(page).toHaveURL(/\/markets$/);
  const guide = page.getByRole('dialog').filter({ hasText: '新手引导' });
  await expect(guide, '新客户第一次进来弹出新手引导（C83）').toBeVisible();
  await guide.getByRole('button', { name: '跳过', exact: true }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  return email;
}

/** 浏览器里的登录 Cookie 做成接口上下文（充值等用接口做） */
async function apiOf(ctx: BrowserContext): Promise<APIRequestContext> {
  const c = (await ctx.cookies()).find((x) => x.name === 'fc_session');
  expect(c, '登录 Cookie').toBeTruthy();
  return withCookie(`fc_session=${c!.value}`);
}

/** 主操作按钮完整地在可视区里（C79，不滚动） */
async function expectInViewport(page: Page, testId: string, where: string): Promise<void> {
  // 抽屉有进场动画：等动画放完再量，否则量到的是动画中途的位置
  await page.waitForFunction(() => document.getAnimations().every((a) => a.playState !== 'running' || !((a.effect as KeyframeEffect | null)?.target as Element | null)?.closest('[role=dialog]')));
  const b = await page.getByTestId(testId).boundingBox();
  const v = page.viewportSize()!;
  expect(b, `${where}：找到按钮`).toBeTruthy();
  expect(b!.y + b!.height, `${where}：按钮底边 ${Math.round(b!.y + b!.height)} ≤ 视口高 ${v.height}`).toBeLessThanOrEqual(v.height);
  expect(b!.y, `${where}：按钮顶边在视口里`).toBeGreaterThanOrEqual(0);
}

/** 截止 → 事件结束 → 提议"是" → 确认 → 公示期满结算（和 settle.spec.ts 同一套接口） */
async function resolveYes(anon: APIRequestContext, m: { no: string; yes: string }): Promise<void> {
  await json(await anon.post('/api/e2e/clock/advance', { data: { millis: 120_000 } }), '拨到截止');
  await json(await anon.post('/api/e2e/markets/tick'), '截止任务');
  await json(await anon.post('/api/e2e/clock/advance', { data: { millis: 60_000 } }), '拨到事件结束');
  await json(await anon.post('/api/e2e/markets/tick'), '事件结束任务');
  const admin = await adminSession(anon);
  const proposed = await json<{ detail: { summary: { proposal: { proposalId: string } } } }>(
    await admin.post(`/api/admin/resolve/cases/${m.no}/proposals`, { data: { optionNo: m.yes, evidenceUrls: ['https://example.org/official'], note: '官方公布：是' } }),
    '提议',
  );
  await json(
    await admin.post(`/api/admin/resolve/cases/${m.no}/proposals/${proposed.detail.summary.proposal.proposalId}/confirm`, { data: { checked: true, reason: '核对官网' } }),
    '确认',
  );
  await json(await anon.post('/api/e2e/clock/advance', { data: { millis: 120_000 } }), '拨到争议截止');
  await json(await anon.post('/api/e2e/resolve/tick'), '公示期满结算');
  const o = await json<{ settlePhase: string }>(await admin.get(`/api/admin/markets/${m.no}/settlement`), '结算情况');
  if (o.settlePhase === 'paying') await json(await anon.post('/api/e2e/settle/tick'), '派彩（分批模式）');
  await admin.dispose();
}

test('手机 390：注册 → 充值 → 抽屉下注 → 奖池实时更新 → 结算 → 我的下注看到押中和派彩', async ({ browser }) => {
  const anon = await request.newContext({ baseURL: STUB_URL });
  const ctx = await browser.newContext({ baseURL: CLIENT_URL, ignoreHTTPSErrors: DEPLOYED, viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, locale: 'zh-CN', timezoneId: 'Asia/Shanghai', serviceWorkers: 'block' });
  const page = await ctx.newPage();
  try {
    const admin = await step('超管登录', () => adminSession(anon));
    await step('客户在手机上注册（勾选已满 18 岁并同意条款）', () => registerInPage(page, anon, 'mobile'), 120_000);
    const me = await apiOf(ctx);
    await step('充值 200（链替身 TRC20），顶栏的"可用"跟着变', async () => {
      await fund(anon, me, '200');
      await page.goto('/markets');
      await expect(page.locator('.mbar-bal')).toContainText('200.00');
    });
    const title = `客户端主线 ${uniqueId()}`;
    const m = await step('后台建一个二选一市场并上架', () => listMarket(anon, admin, title));
    await step('市场列表：搜索到这个市场，点卡片上的"是"进详情、直接弹出下注抽屉；确认按钮在首屏内（C79）', async () => {
      await page.goto('/markets');
      await page.getByRole('searchbox', { name: '搜索市场' }).fill(title);
      const card = page.getByTestId('market-card').filter({ hasText: title });
      await expect(card).toHaveCount(1);
      await expectNoHorizontalScroll(page, '手机市场列表');
      await card.getByRole('button', { name: /^是/ }).click();
      const sheet = page.getByRole('dialog');
      await expect(sheet).toBeVisible();
      await sheet.getByLabel('金额（USDT）').fill('50');
      await expect(sheet.getByText('赢了约回')).toBeVisible();
      await expectInViewport(page, 'bet-confirm', '390×844 下注抽屉');
      await sheet.getByTestId('bet-confirm').click();
      await expect(page.getByText('下注成功', { exact: true })).toBeVisible();
      // S14（MF-5，P3）：抽屉关闭、弹出挑战卡（可以关掉）
      const challenge = page.getByRole('dialog', { name: '下注成功 · 挑战卡' });
      await expect(challenge).toBeVisible();
      await challenge.getByRole('button', { name: '关闭' }).click();
      await expect(page.getByRole('dialog')).toHaveCount(0);
    });
    await step('另一个客户押"否" 100：详情页的总奖池 2 秒内自动变成 150.00（WS 推送，AC-17），不用刷新', async () => {
      await expect(page.locator('.md-stats')).toContainText('50.00');
      const other = await clientSession(anon, 'other');
      await fund(anon, other.api, '100');
      await bet(other.api, m.no, m.nope, '100');
      await expect(page.locator('.md-stats')).toContainText('150.00', { timeout: 5_000 });
      await other.api.dispose();
    });
    await step('我的下注：进行中有这一笔', async () => {
      await page.goto('/bets');
      await expect(page.getByText(title).first()).toBeVisible();
    });
    await step('截止、平台裁决"是"、公示期满结算', () => resolveYes(anon, m), 90_000);
    await step('我的下注：已结算 → 押中、派彩 145.00（50 + 100 − 抽成 5.00）；汇总净盈亏 +95.00', async () => {
      await page.goto('/bets?tab=settled');
      const card = page.locator('.mb-card').filter({ hasText: title });
      await expect(card).toContainText('押中');
      await expect(card).toContainText('派彩 145.00');
      await expect(card).toContainText('抽成 5.00');
      await expect(page.locator('.mb-sum')).toContainText('+95.00');
      await expectNoHorizontalScroll(page, '手机我的下注');
    });
    await step('详情：结果"是"、结算合计（奖池 150、派彩 145、抽成 5），我的派彩 145', async () => {
      await page.goto(`/markets/${m.no}`);
      await expect(page.getByText('结果：是').first()).toBeVisible();
      await expect(page.getByText('奖池合计', { exact: true })).toBeVisible();
      await expect(page.locator('.md-banner-r')).toContainText('145.00');
    });
    await step('通知中心：点"已结算：你押中了"回到这个市场（需求 P6）', async () => {
      await page.goto('/me/notifications');
      await page.getByRole('button', { name: /你押中了/ }).first().click();
      await expect(page).toHaveURL(new RegExp(`/markets/${m.no}$`));
    });
    await admin.dispose();
    await me.dispose();
  } finally {
    await anon.post('/api/e2e/clock/reset');
    await ctx.close();
    await anon.dispose();
  }
});

test('开赛提醒（#23）：只给用邮箱和密码登录过的客户；订阅、退订、到开放时间发一封邮件、邮件里的退订链接', async ({ browser }) => {
  const anon = await request.newContext({ baseURL: STUB_URL });
  const ctx = await browser.newContext({ baseURL: CLIENT_URL, ignoreHTTPSErrors: DEPLOYED, viewport: { width: 1366, height: 768 }, locale: 'zh-CN', serviceWorkers: 'block' });
  const page = await ctx.newPage();
  try {
    const admin = await step('超管登录', () => adminSession(anon));
    /** 还没开放的市场：开放 = 现在 + openMin 分钟 */
    const upcoming = async (name: string, openMin: number) => {
      const now = new Date((await json<{ now: string }>(await anon.get('/api/e2e/clock'), '读时钟')).now).getTime();
      const at = (min: number) => new Date(now + min * 60_000).toISOString();
      const d = await json<{ publicNo: string }>(
        await admin.post('/api/admin/markets', {
          data: {
            category: 'MACRO',
            title: t(name),
            rules: t('按官方公布为准'),
            resolutionMode: 'PLATFORM',
            sourcePrimary: 'example.org',
            sourceBackup: 'example.net',
            openAt: at(openMin),
            closeAt: at(openMin + 60),
            eventStartAt: at(openMin + 60),
            eventEndAt: at(openMin + 90),
            disputeWindowMinutes: 2,
            options: [
              { label: t('是'), winCondition: t('公布为是') },
              { label: t('否'), winCondition: t('公布为否') },
            ],
          },
        }),
        '建草稿',
      );
      await json(await admin.post(`/api/admin/markets/${d.publicNo}/publish`, { data: { checked: true, overLimit: true } }), '上架');
      return d.publicNo;
    };
    const a = await step('后台上架两个还没开放的市场（10 分钟、20 分钟后开放）', () => upcoming(`提醒 A ${uniqueId()}`, 10));
    const b = await upcoming(`提醒 B ${uniqueId()}`, 20);
    const email = await step('客户注册（注册时的自动登录）', () => registerInPage(page, anon, 'remind'), 120_000);
    await step('注册后还没用邮箱和密码登录过：不能订阅，写明原因（SQ-20）', async () => {
      await page.goto(`/markets/${a}`);
      await expect(page.getByText('开赛提醒只对用邮箱和密码登录过的账户开放')).toBeVisible();
      await expect(page.getByTestId('remind-on')).toHaveCount(0);
    });
    await step('退出，再用邮箱和密码登录', async () => {
      await page.goto('/me');
      await page.getByRole('button', { name: '退出登录' }).click();
      await page.getByRole('dialog').getByRole('button', { name: '退出登录' }).click();
      await page.getByLabel('邮箱', { exact: true }).fill(email);
      await page.getByLabel('密码', { exact: true }).fill(CLIENT_PASSWORD);
      await page.getByRole('button', { name: '登录', exact: true }).click();
      await expect(page).toHaveURL(/\/markets$/);
    });
    await step('两个市场都点"开放时提醒我"', async () => {
      for (const no of [a, b]) {
        await page.goto(`/markets/${no}`);
        await page.getByTestId('remind-on').click();
        await expect(page.getByTestId('remind-off')).toBeVisible();
      }
    });
    await step('通知偏好：两个都是"订阅中"；退订 B', async () => {
      await page.goto('/me/notify-prefs');
      const box = page.getByTestId('reminders');
      await expect(box.getByText('订阅中')).toHaveCount(2);
      await box.locator('li').filter({ hasText: '提醒 B' }).getByRole('button', { name: '退订' }).click();
      await expect(box.locator('li').filter({ hasText: '提醒 B' })).toContainText('已退订');
    });
    let body = '';
    await step('拨到两个市场都开放之后、跑一轮提醒：A 发出一封邮件（市场链接、退订链接完整），B 退订了不发；再跑一轮不重复', async () => {
      await json(await anon.post('/api/e2e/clock/advance', { data: { millis: 21 * 60_000 } }), '拨到开放之后');
      await json(await anon.post('/api/e2e/reminders/tick'), '提醒任务');
      await json(await anon.post('/api/e2e/reminders/tick'), '再跑一轮');
      await expect.poll(async () => (await e2e.mailSubjects(anon, email)).filter((s) => s.includes('场次提醒')).length, { message: '等提醒邮件', timeout: 20_000 }).toBe(1);
      const mail = await e2e.mailLast(anon, email, '场次提醒');
      expect(mail?.subject).toContain('提醒 A');
      body = mail!.body;
      expect(body).toContain(`/markets/${a}`);
      expect(body).toMatch(/\/unsubscribe\?t=[0-9a-f]{32}/);
    });
    await step('邮件里的退订链接：不用登录也能打开（这一场已经发过，写明不会再发）', async () => {
      const link = /(\/unsubscribe\?t=[0-9a-f]{32})/.exec(body)![1];
      const guest = await browser.newContext({ baseURL: CLIENT_URL, ignoreHTTPSErrors: DEPLOYED, locale: 'zh-CN', serviceWorkers: 'block' });
      const g = await guest.newPage();
      await g.goto(link);
      await expect(g.getByTestId('unsub-done')).toContainText('已经发过');
      await g.goto('/unsubscribe?t=0123456789abcdef0123456789abcdef');
      await expect(g.getByTestId('unsub-invalid')).toBeVisible();
      await guest.close();
    });
    await admin.dispose();
  } finally {
    await anon.post('/api/e2e/clock/reset');
    await ctx.close();
    await anon.dispose();
  }
});
