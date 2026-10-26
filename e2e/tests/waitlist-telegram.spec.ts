/**
 * 上线前等候页兼数据页（forecast S16，MF-8、MF-17；需求 P13、AC-95、AC-96；SQ-22）和官方 Telegram 机器人（MF-7；第 6.3 节、AC-89、AC-90）。
 * 两种形态都跑（tests/targets.ts）。Telegram 是后端里的替身（growth/telegram/E2eTelegram），不连真实的 Telegram；币安行情也是替身（E2eBoard）。
 * 1. 手机 390：不登录打开 /data（BTC 现价、过去 N 小时涨跌比例和手算一致）→ 留邮箱 → 邮件里的确认链接 → 按"确认订阅" →
 *    后台"等候页订阅"按"发送上线通知"（高危：原因 + 勾选）→ 任务发信：只发一封"正式上线了"，再跑不重发，接口再按 409 →
 *    邮件里的退订链接（不用登录）→ 数据页变成"我们今天的场次"。
 * 2. 机器人：/btc 有回复（现价、涨跌、BTC 场次的链接）；拉进群、管理员 /broadcast_on；模板审定 + 打开频道后，带"今日场次"标记的市场结束 →
 *    替身收到频道和群的开奖卡（图片 + 文字 + 带来源的链接），每个渠道一次；没有标记的不发。
 * 3. 没有 token：机器人不取消息、不发帖；健康检查 status 仍是 UP，alerts 里 telegram.bot 提示未配置。
 */
import { expect, request, test, type APIRequestContext } from '@playwright/test';
import { adminSession, json, t } from './api';
import { adminLogin } from './admin';
import { e2e, step, uniqueId } from './support';
import { ADMIN_URL, CLIENT_URL, DEPLOYED, STUB_URL } from './targets';

const mobile = { viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true } as const;

type Sent = { method: string; chatId: string; text: string; photoBytes: number };

/** 过去 24 小时的 1 小时 K 线：开盘 100，收盘按序列（含正好相等）；手算：收盘 ≥ 开盘算涨 */
const CLOSES = ['101', '99', '100', '102.5', '98', '100.01', '99.99', '100', '105', '95', '100', '100', '101', '102', '97', '96', '100.5', '99.5', '103', '100', '98.7', '101.2', '100', '90'];
const UP = CLOSES.filter((c) => Number(c) >= 100).length;

async function setKlines(anon: APIRequestContext, price = '65432.10'): Promise<void> {
  await json(await anon.post('/api/e2e/board/klines', { data: { candles: CLOSES.map((c) => ['100', c]), price } }), '设币安 K 线');
  const r = await json<{ ok: boolean }>(await anon.post('/api/e2e/board/refresh'), '刷新行情');
  expect(r.ok, '行情刷新成功').toBe(true);
}

/** 上架一个带"今日场次"标记的平台裁决市场（开放 = 现在 − 1 分钟，截止 = 现在 + 2 分钟） */
async function slateMarket(anon: APIRequestContext, admin: APIRequestContext, title: string, todaySlate = true): Promise<string> {
  const now = new Date((await json<{ now: string }>(await anon.get('/api/e2e/clock'), '读时钟')).now);
  const at = (ms: number) => new Date(now.getTime() + ms).toISOString();
  const d = await json<{ publicNo: string }>(
    await admin.post('/api/admin/markets', {
      data: {
        category: 'MACRO',
        title: t(title),
        rules: t('按官方公布为准；无法判定的作废退款'),
        resolutionMode: 'PLATFORM',
        sourcePrimary: 'example.org',
        sourceBackup: 'example.net',
        openAt: at(-60_000),
        closeAt: at(120_000),
        eventStartAt: at(120_000),
        eventEndAt: at(180_000),
        disputeWindowMinutes: 2,
        options: [
          { label: t('是'), winCondition: t('公布为是') },
          { label: t('否'), winCondition: t('公布为否') },
        ],
      },
    }),
    '建草稿',
  );
  await json(await admin.post(`/api/admin/markets/${d.publicNo}/publish`, { data: { checked: true, overLimit: true, todaySlate } }), todaySlate ? '上架（列入今日场次）' : '上架（没有标记）');
  return d.publicNo;
}

async function cancel(admin: APIRequestContext, no: string): Promise<void> {
  await json(await admin.post(`/api/admin/markets/${no}/cancel`, { data: { reason: '端到端：结束这个市场，看开奖卡' } }), '取消市场');
}

async function sent(anon: APIRequestContext, chatId?: string): Promise<Sent[]> {
  return json<Sent[]>(await anon.get(`/api/e2e/telegram/sent${chatId ? `?chatId=${encodeURIComponent(chatId)}` : ''}`), '替身收到的消息');
}

async function tick(anon: APIRequestContext): Promise<{ polled: number; posted: number; configured: boolean }> {
  return json(await anon.post('/api/e2e/telegram/tick'), 'Telegram 跑一轮');
}

async function push(anon: APIRequestContext, u: Record<string, unknown>): Promise<void> {
  await json(await anon.post('/api/e2e/telegram/updates', { data: u }), '排一条 Telegram 消息');
}

test('等候页：不登录留邮箱 → 确认 → 后台发上线通知只发一次 → 退订', async ({ browser }) => {
  const anon = await request.newContext({ baseURL: STUB_URL });
  const ctx = await browser.newContext({ baseURL: CLIENT_URL, ignoreHTTPSErrors: DEPLOYED, ...mobile, locale: 'zh-CN', serviceWorkers: 'block' });
  const actx = await browser.newContext({ baseURL: ADMIN_URL, ignoreHTTPSErrors: DEPLOYED, viewport: { width: 1440, height: 900 }, locale: 'zh-CN', serviceWorkers: 'block' });
  const page = await ctx.newPage();
  const email = `wl_${uniqueId()}@example.com`;
  try {
    await json(await anon.post('/api/e2e/clock/reset'), '时钟复原');
    await json(await anon.post('/api/e2e/waitlist/reset-launch'), '回到还没上线');
    await step('币安行情（替身）', () => setKlines(anon));

    await step('不登录打开 /data：BTC 现价、过去 24 小时的涨跌比例和手算一致、来源和更新时间；即将上线', async () => {
      await page.goto('/data');
      await expect(page.getByTestId('btc-price')).toContainText('65,432.10');
      const pct = ((UP * 100) / 24).toFixed(1);
      await expect(page.getByTestId('btc-hours')).toContainText(`涨 ${UP} 小时（${pct}%）`);
      await expect(page.getByTestId('btc-hours')).toContainText(`跌 ${24 - UP} 小时`);
      await expect(page.getByText(/数据来自币安公开行情/)).toBeVisible();
      await expect(page.getByRole('heading', { name: '即将上线' })).toBeVisible();
      await expect(page.getByText(/Polymarket/)).toHaveCount(0);
    });

    await step('留邮箱：页面说明去邮箱确认，只收到一封确认邮件（还不是订阅）', async () => {
      await page.getByLabel('邮箱', { exact: true }).fill(email);
      await page.getByTestId('wl-submit').click();
      await expect(page.getByTestId('wl-done')).toContainText(email);
      await expect.poll(async () => (await e2e.mailSubjects(anon, email)).length, { message: '等确认邮件', timeout: 20_000 }).toBe(1);
      expect((await e2e.mailSubjects(anon, email))[0]).toContain('请确认订阅');
    });

    await step('邮件里的确认链接：打开不自动确认，按"确认订阅"才确认', async () => {
      const mail = await e2e.mailLast(anon, email, '请确认订阅');
      const link = /(\/data\/confirm\?t=[0-9a-f]{32})/.exec(mail!.body)![1];
      expect(mail!.body).toMatch(/\/data\/unsubscribe\?t=[0-9a-f]{32}/);
      const guest = await browser.newContext({ baseURL: CLIENT_URL, ignoreHTTPSErrors: DEPLOYED, locale: 'zh-CN', serviceWorkers: 'block' });
      const g = await guest.newPage();
      await g.goto(link);
      await expect(g.getByTestId('wl-confirm')).toBeVisible();
      await expect(g.getByTestId('wl-confirmed')).toHaveCount(0);
      await g.getByTestId('wl-confirm').click();
      await expect(g.getByTestId('wl-confirmed')).toContainText('已确认');
      await guest.close();
    });

    const apage = await actx.newPage();
    await step('后台"等候页订阅"：发送上线通知（高危：原因 + 勾选）', async () => {
      await adminLogin(apage);
      await apage.goto('/admin/waitlist');
      await expect(apage.getByRole('heading', { name: '等候页订阅' })).toBeVisible();
      await expect(apage.getByText('w***@example.com').first()).toBeVisible();
      await apage.getByTestId('wl-launch').click();
      const dialog = apage.getByRole('dialog');
      await expect(dialog).toContainText('全站只能发一次');
      await dialog.getByLabel('操作原因').fill('端到端：正式上线');
      await dialog.getByRole('checkbox').check();
      const done = apage.waitForResponse((r) => r.url().includes('/api/admin/waitlist/launch') && r.request().method() === 'POST');
      await dialog.getByRole('button', { name: '确认发送' }).click();
      expect((await done).ok()).toBe(true);
      await expect(apage.getByTestId('wl-launched')).toContainText('端到端：正式上线');
      await expect(apage.getByTestId('wl-launch')).toHaveCount(0);
    });

    await step('任务发信：这个邮箱只收到一封"正式上线了"；再跑一轮、再按一次（409）都不重发', async () => {
      await json(await anon.post('/api/e2e/waitlist/launch-tick'), '发上线通知');
      await json(await anon.post('/api/e2e/waitlist/launch-tick'), '再跑一轮');
      const admin = await adminSession(anon);
      const again = await admin.post('/api/admin/waitlist/launch', { data: { checked: true, reason: '再按一次' } });
      expect(again.status()).toBe(409);
      expect(await again.text()).toContain('LAUNCH_ALREADY_SENT');
      await admin.dispose();
      await expect.poll(async () => (await e2e.mailSubjects(anon, email)).filter((s) => s.includes('正式上线了')).length, { message: '等上线通知', timeout: 20_000 }).toBe(1);
      await new Promise((r) => setTimeout(r, 2000));
      expect((await e2e.mailSubjects(anon, email)).filter((s) => s.includes('正式上线了'))).toHaveLength(1);
      expect((await e2e.mailSubjects(anon, email)).filter((s) => s.includes('场次提醒')), '不发开赛提醒（SQ-22）').toHaveLength(0);
    });

    await step('上线通知里的退订链接：不用登录就能退订', async () => {
      const mail = await e2e.mailLast(anon, email, '正式上线了');
      expect(mail!.body).toContain('/markets?src=waitlist&cmp=launch');
      const link = /(\/data\/unsubscribe\?t=[0-9a-f]{32})/.exec(mail!.body)![1];
      const guest = await browser.newContext({ baseURL: CLIENT_URL, ignoreHTTPSErrors: DEPLOYED, locale: 'zh-CN', serviceWorkers: 'block' });
      const g = await guest.newPage();
      await g.goto(link);
      await expect(g.getByTestId('wl-unsub-done')).toContainText('已退订');
      await g.goto('/data/unsubscribe?t=0123456789abcdef0123456789abcdef');
      await expect(g.getByTestId('wl-unsub-invalid')).toBeVisible();
      await guest.close();
    });

    await step('上线以后：数据页显示"我们今天的场次"，不再收邮箱', async () => {
      await page.goto('/data');
      await expect(page.getByRole('heading', { name: '我们今天的场次' })).toBeVisible();
      await expect(page.getByTestId('wl-submit')).toHaveCount(0);
    });
  } finally {
    await json(await anon.post('/api/e2e/waitlist/reset-launch'), '回到还没上线');
    await ctx.close();
    await actx.close();
    await anon.dispose();
  }
});

test('机器人：/btc 有回复；拉进群开播报；模板审定 + 打开频道后，今日场次的市场结束 → 频道和群收到开奖卡', async () => {
  const anon = await request.newContext({ baseURL: STUB_URL });
  const admin = await adminSession(anon);
  const group = -1001000000000 - Math.floor(Math.random() * 1_000_000);
  try {
    await json(await anon.post('/api/e2e/clock/reset'), '时钟复原');
    await json(await anon.post('/api/e2e/telegram/reset'), '清空替身');
    await step('币安行情（替身）', () => setKlines(anon, '67000.50'));

    await step('/btc：回 BTC 现价、过去 24 小时的涨跌比例、BTC 场次或"现在没有"、链接；没有下注', async () => {
      await push(anon, { chatId: 9001, chatType: 'private', fromId: 9001, languageCode: 'zh-hans', text: '/btc' });
      const r = await tick(anon);
      expect(r.polled).toBeGreaterThanOrEqual(1);
      const msg = (await sent(anon, '9001')).find((s) => s.method === 'sendMessage');
      expect(msg, '机器人回复了').toBeTruthy();
      expect(msg!.text).toContain('BTC 现价 67000.50 USDT');
      expect(msg!.text).toContain(`过去 24 小时：${UP} 小时涨、${24 - UP} 小时跌`);
      expect(msg!.text).toMatch(/当前场次|现在没有开放的 BTC 场次/);
      expect(msg!.text).toMatch(/\/markets(\/\d+)?\?src=telegram&cmp=bot/);
    });

    await step('/start 带参数：回说明（不能在 Telegram 里下注），链接带 start（来源统计）', async () => {
      await push(anon, { chatId: 9002, chatType: 'private', fromId: 9002, languageCode: 'en', text: '/start s-tg__c-e2e' });
      await tick(anon);
      const msg = (await sent(anon, '9002')).find((s) => s.method === 'sendMessage');
      expect(msg!.text).toContain('there is no betting in Telegram');
      expect(msg!.text).toContain('start=s-tg__c-e2e');
    });

    await step('拉进群；非管理员不能开播报；管理员 /broadcast_on 开启', async () => {
      await push(anon, { chatId: group, chatType: 'supergroup', title: 'E2E 群', myChatMember: 'member' });
      await push(anon, { chatId: group, chatType: 'supergroup', title: 'E2E 群', fromId: 501, languageCode: 'zh-hans', text: '/broadcast_on' });
      await tick(anon);
      expect((await sent(anon, String(group))).map((s) => s.text).join('\n')).toContain('只有群管理员');
      await json(await anon.post('/api/e2e/telegram/admins', { data: { chatId: group, userId: 501 } }), '设群管理员');
      await push(anon, { chatId: group, chatType: 'supergroup', title: 'E2E 群', fromId: 501, languageCode: 'zh-hans', text: '/broadcast_on' });
      await tick(anon);
      expect((await sent(anon, String(group))).map((s) => s.text).join('\n')).toContain('已开启开奖播报');
    });

    const channel = `@qc_e2e_${uniqueId().slice(-6).toLowerCase()}`.replace(/[^@a-z0-9_]/g, '_');
    await step('后台：审定中文开奖卡模板（预览的指纹）、填中文频道并打开', async () => {
      const ended = await slateMarket(anon, admin, `S16 预览 ${uniqueId()}`);
      await cancel(admin, ended);
      const p = await json<{ fingerprint: string; text: string }>(await admin.get('/api/admin/telegram/templates/RESULT/zh-CN/preview'), '模板预览');
      expect(p.text.length).toBeGreaterThan(10);
      await json(await admin.post('/api/admin/telegram/templates/RESULT/zh-CN/approve', { data: { fingerprint: p.fingerprint, checked: true, reason: '端到端审定' } }), '审定');
      await json(await admin.put('/api/admin/telegram/targets/CHANNEL_ZH', { data: { chatId: channel, enabled: true, reason: '端到端频道' } }), '打开中文频道');
      await tick(anon);
    });

    await step('今日场次的市场结束 → 频道和群各收到一张开奖卡（图片 + 带来源参数的链接）；没有标记的不发；再跑不重发', async () => {
      await new Promise((r) => setTimeout(r, 1100)); // 结束时间要晚于打开开关的时间（毫秒精度）
      const no = await slateMarket(anon, admin, `S16 开奖卡 ${uniqueId()}`);
      const plain = { publicNo: await slateMarket(anon, admin, `S16 没有标记 ${uniqueId()}`, false) };
      await cancel(admin, no);
      await cancel(admin, plain.publicNo);
      await tick(anon);
      const toChannel = (await sent(anon, channel)).filter((s) => s.method === 'sendPhoto' && s.text.includes(`/markets/${no}?`));
      expect(toChannel, '频道收到一张').toHaveLength(1);
      expect(toChannel[0].photoBytes).toBeGreaterThan(10_000);
      expect(toChannel[0].text).toContain('已取消');
      expect(toChannel[0].text).toContain('src=telegram&cmp=channel&ref=zh');
      const toGroup = (await sent(anon, String(group))).filter((s) => s.method === 'sendPhoto' && s.text.includes(`/markets/${no}?`));
      expect(toGroup, '群收到一张').toHaveLength(1);
      expect(toGroup[0].text).toContain(`cmp=group&ref=${group}`);
      expect((await sent(anon)).filter((s) => s.text.includes(`/markets/${plain.publicNo}?`)), '没有标记的不发').toHaveLength(0);
      await tick(anon);
      expect((await sent(anon, channel)).filter((s) => s.method === 'sendPhoto' && s.text.includes(`/markets/${no}?`)), '不重发').toHaveLength(1);
      const log = await json<{ items: { marketNo: string; target: string; status: string; postUrl: string | null }[] }>(
        await admin.get('/api/admin/telegram/posts?status=sent'),
        '发布日志',
      );
      const mine = log.items.find((i) => i.marketNo === no && i.target === 'CHANNEL_ZH');
      expect(mine?.postUrl).toMatch(new RegExp(`^https://t\\.me/${channel.slice(1)}/\\d+$`));
    });
  } finally {
    await admin.put('/api/admin/telegram/targets/CHANNEL_ZH', { data: { chatId: null, enabled: false, reason: '端到端结束' } });
    await admin.dispose();
    await anon.dispose();
  }
});

test('没有 token：机器人不启动、不发帖；健康检查 status 仍是 UP，alerts 提示未配置', async () => {
  const anon = await request.newContext({ baseURL: STUB_URL });
  try {
    await json(await anon.post('/api/e2e/telegram/reset'), '清空替身');
    await step('模拟没有配置 FC_TELEGRAM_BOT_TOKEN', async () => {
      const r = await json<{ configured: boolean }>(await anon.post('/api/e2e/telegram/configured', { data: { configured: false } }), '去掉 token');
      expect(r.configured).toBe(false);
    });
    await step('排一条 /btc：不取消息、不回复', async () => {
      await push(anon, { chatId: 9100, chatType: 'private', fromId: 9100, text: '/btc' });
      const r = await tick(anon);
      expect(r).toMatchObject({ polled: 0, posted: 0, configured: false });
      expect(await sent(anon)).toHaveLength(0);
    });
    await step('健康检查：status UP；alerts 的 telegram.bot 是 DOWN', async () => {
      const h = await json<{ status: string; alerts?: Record<string, string> }>(await anon.get('/api/health'), '健康检查');
      expect(h.status).toBe('UP');
      expect(h.alerts?.['telegram.bot']).toBe('DOWN');
    });
    await step('恢复 token：alerts 回到 UP，消息照常处理', async () => {
      await json(await anon.post('/api/e2e/telegram/configured', { data: { configured: true } }), '恢复 token');
      const h = await json<{ alerts?: Record<string, string> }>(await anon.get('/api/health'), '健康检查');
      expect(h.alerts?.['telegram.bot']).toBe('UP');
      const r = await tick(anon);
      expect(r.polled).toBeGreaterThanOrEqual(1);
    });
  } finally {
    await anon.post('/api/e2e/telegram/configured', { data: { configured: true } });
    await anon.dispose();
  }
});
