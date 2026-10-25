/**
 * 主流程（复制源的 S19 主流程，forecast S1 删除了下单、止盈止损、平仓几步，剩下账户、充值、KYC、提现）：
 * 客户在 390px 宽的手机上：注册（邮箱验证码）→ 退出后登录 → 充值到账 → 提交 KYC → 加白名单 → 提现；
 * 超管在电脑上：首次登录改密码 → 审核 KYC → 出金审核 → 查看客户；广播成功、链上确认后客户看到提现已完成。
 * 品牌：标题、页面文字、邮件标题是现在的品牌名（web/client/src/lib/brand.ts），没有旧品牌名（scripts/brand-check.json）。
 * 注册后第一次进来弹出新手引导（这里点"跳过"）。
 * 每轮用新邮箱、新证件号，不依赖上一轮的数据。外部依赖（邮件、链、提现广播）由后端 e2e profile 的替身代替。
 * 两种形态共用（tests/targets.ts）：默认经 server.mjs；部署形态经 Nginx 镜像（HTTPS、两个域名），另外检查登录 Cookie 带 Secure、
 * 两个前端顶部有"测试环境"标识条。下注、结算的步骤在 S11 以后加回来。
 */
import { expect, request as apiRequest, test, type BrowserContext, type Page } from '@playwright/test';
import { adminLogin, cjk, confirmAndWait, setConfig } from './admin';
import { BRAND, CLIENT_PASSWORD, e2e, expectNoHorizontalScroll, expectNoHorizontalScrollWithScrollbar, OLD_BRAND, PNG_1PX, Recorder, step, trackSocketOpen, uniqueId, waitSocketOpen } from './support';
import { ADMIN_URL, CLIENT_URL, DEPLOYED, EXPECT_ENV_BANNER, STUB_URL } from './targets';

const DEPOSIT = '1000';
const WITHDRAW = '100';
/** 白名单地址：任意合法的以太坊地址（链替身不会真的转账） */
const WITHDRAW_TO = '0x00000000000000000000000000000000e2e0beef';

/**
 * 新手引导：身份认证"未提交"的新客户第一次进来自动弹出；点"跳过"关掉，记在这个浏览器，之后不再弹。
 * 弹窗要等认证状态取回来才出现，这里等它出现再关。
 */
async function skipOnboarding(page: Page): Promise<void> {
  const guide = page.getByRole('dialog').filter({ hasText: '新手引导' });
  await expect(guide, '新客户第一次进来弹出新手引导').toBeVisible();
  // M1 起第一步是玩法说明
  await expect(guide.getByText('在这里押一件事的结果')).toBeVisible();
  await guide.getByRole('button', { name: '跳过', exact: true }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
}

/** 部署形态：登录 Cookie 必须是 HttpOnly + Secure（测试环境 FC_COOKIE_SECURE=true，经 HTTPS 才能保存） */
async function expectSecureCookie(ctx: BrowserContext, name: string): Promise<void> {
  if (!DEPLOYED) return;
  const c = (await ctx.cookies()).find((x) => x.name === name);
  expect(c, `登录后应有 Cookie ${name}`).toBeTruthy();
  expect(c?.secure, `${name} 带 Secure`).toBe(true);
  expect(c?.httpOnly, `${name} 带 HttpOnly`).toBe(true);
}

/** 后端设了 FC_ENV_LABEL 时，页面顶部显示"测试环境"标识条 */
async function expectEnvBanner(page: Page): Promise<void> {
  if (!EXPECT_ENV_BANNER) return;
  await expect(page.getByTestId('env-banner')).toBeVisible();
  await expect(page.getByTestId('env-banner')).toContainText('测试环境');
}

test('客户主流程（手机 390px）和后台审核', async ({ browser }) => {
  const id = uniqueId();
  const email = `e2e-${id}@example.com`;
  const client = await browser.newContext({ baseURL: CLIENT_URL, ignoreHTTPSErrors: DEPLOYED, viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, locale: 'zh-CN', serviceWorkers: 'block' });
  const admin = await browser.newContext({ baseURL: ADMIN_URL, ignoreHTTPSErrors: DEPLOYED, viewport: { width: 1440, height: 900 }, locale: 'zh-CN', serviceWorkers: 'block' });
  // 替身接口：测试代码直接访问（部署形态是后端 127.0.0.1:8080，Nginx 不转发 /api/e2e/）
  const request = await apiRequest.newContext({ baseURL: STUB_URL });
  await trackSocketOpen(client);
  const page = await client.newPage();
  const adminPage = await admin.newPage();
  const rec = new Recorder(page);
  const adminRec = new Recorder(adminPage);
  test.info().annotations.push({ type: '客户邮箱', description: email });

  try {
    await step('后台：超管登录（首次登录先改密码）', async () => {
      const first = await adminLogin(adminPage);
      test.info().annotations.push({ type: '超管', description: first ? '首次登录，已改密码' : '已改过密码，直接登录' });
      await expectSecureCookie(admin, 'fc_admin_session');
      await expectEnvBanner(adminPage);
      await expect(adminPage, '后台标签页标题').toHaveTitle(`${BRAND} 管理后台`);
      // 测试不等 2 小时冷静期和 24 小时白名单生效：超管在系统配置里改成 0（规则本身由后端测试覆盖，AC-17、AC-18）
      await setConfig(adminPage, 'withdraw.coolingHours', '0');
      await setConfig(adminPage, 'whitelist.effectiveHours', '0');
    });

    await step('客户：用邮箱验证码注册', async () => {
      await page.goto('/register');
      await page.getByLabel('邮箱', { exact: true }).fill(email);
      const sentAt = new Date(Date.now() - 1000).toISOString();
      await page.getByRole('button', { name: '发送验证码' }).click();
      await expect(page.getByText(`验证码已发送到 ${email}`)).toBeVisible();
      const code = await e2e.mailCode(request, email, sentAt);
      await page.getByLabel('邮箱验证码').fill(code);
      await page.getByLabel('密码', { exact: true }).fill(CLIENT_PASSWORD);
      await page.getByLabel('确认密码').fill(CLIENT_PASSWORD);
      await page.getByLabel('名', { exact: true }).fill('测试');
      await page.getByLabel('姓', { exact: true }).fill('端到端');
      // 出生日期是 年 / 月 / 日 三个数字框：像手机上一样点一下年份框，逐个输入数字，输满自动跳到下一格
      const birth = page.getByRole('group', { name: '出生日期' });
      await birth.getByLabel('年', { exact: true }).tap();
      await page.keyboard.type('19900127');
      await expect(birth.getByLabel('日', { exact: true }), '输满自动跳格：日期最后两位落在"日"').toHaveValue('27');
      await expect(birth.getByLabel('月', { exact: true })).toHaveValue('01');
      await page.getByLabel('国籍').click();
      await page.getByRole('textbox', { name: '搜索' }).fill('CN');
      await page.getByRole('option').first().click();
      await page.getByRole('checkbox').check();
      await expectNoHorizontalScroll(page, '注册页');
      await page.getByRole('button', { name: '注册', exact: true }).click();
      // M1 起首页是市场列表（/home 跳到 /markets）
      await expect(page).toHaveURL(/\/markets$/);
      await skipOnboarding(page);
    });

    await step('客户：退出登录，再用邮箱和密码登录', async () => {
      await page.goto('/me');
      await page.getByRole('button', { name: '退出登录' }).click();
      await page.getByRole('dialog').getByRole('button', { name: '退出登录' }).click();
      await expect(page).toHaveURL(/\/login$/);
      await page.getByLabel('邮箱', { exact: true }).fill(email);
      await page.getByLabel('密码', { exact: true }).fill(CLIENT_PASSWORD);
      await page.getByRole('button', { name: '登录', exact: true }).click();
      // M1 起首页是市场列表（/home 跳到 /markets）
      await expect(page).toHaveURL(/\/markets$/);
      await expectSecureCookie(client, 'fc_session');
      await expectEnvBanner(page);
      await expectNoHorizontalScroll(page, '首页');
      // 标签页标题和页面文字是现在的品牌名，没有旧品牌名
      await expect(page).toHaveTitle(BRAND);
      expect(await page.locator('body').innerText(), '客户端页面文字').not.toMatch(OLD_BRAND);
      await expect(page.getByRole('dialog'), '新手引导看过（跳过）就不再自动弹').toHaveCount(0);
    });

    await step('客户：电脑最窄宽度 1024 各页面没有横向滚动（AC-31）', async () => {
      // 必须用电脑浏览器的上下文：手机模拟（isMobile）下内容超宽时浏览器会把布局视口放大到内容宽度，scrollWidth 和 clientWidth 一样大，查不出来
      const desk = await browser.newContext({ baseURL: CLIENT_URL, ignoreHTTPSErrors: DEPLOYED, viewport: { width: 1024, height: 800 }, locale: 'zh-CN', serviceWorkers: 'block', storageState: await client.storageState() });
      try {
        const d = await desk.newPage();
        for (const [path, where] of [
          ['/markets', '1024 宽首页（市场列表）'],
          ['/assets', '1024 宽资产页'],
          ['/assets/ledger', '1024 宽资金流水'],
          ['/me', '1024 宽我的'],
        ] as const) {
          await d.goto(path);
          await expect(d.locator('.main')).toBeVisible();
          await d.waitForLoadState('networkidle');
          await expectNoHorizontalScroll(d, where);
          await expectNoHorizontalScrollWithScrollbar(d, where);
        }
      } finally {
        await desk.close();
      }
    });

    let address = '';
    await step('客户：三条链的充值地址（ERC20 和 BSC 相同）', async () => {
      await page.goto('/assets');
      const addr = page.locator('.qr-wrap .addr .num');
      const read = async (net: string) => {
        await page.getByRole('radio', { name: new RegExp(`^${net}`) }).click();
        await expect(addr).toBeVisible();
        return (await addr.innerText()).replace(/\s+/g, '');
      };
      const erc20 = await read('ERC20');
      const bsc = await read('BSC');
      address = await read('TRC20');
      expect(erc20, 'ERC20 地址').toMatch(/^0x[0-9a-fA-F]{40}$/);
      expect(bsc, 'ERC20 和 BSC 是同一个地址（AC-06）').toBe(erc20);
      expect(address, 'TRC20 地址').toMatch(/^T[1-9A-HJ-NP-Za-km-z]{33}$/);
      await expectNoHorizontalScroll(page, '资产页');
    });

    await step(`客户：链上转入 ${DEPOSIT} USDT（TRC20），先显示待确认，确认数够了到账`, async () => {
      // 先等推送连上再转账（每次连上都重新拉取），保留这一步让测试不依赖时间窗口
      await waitSocketOpen(page, '/api/ws/user');
      const r = await e2e.deposit(request, 'TRC20', address, DEPOSIT);
      expect(r.status).toBe('PENDING');
      // 发现待确认的充值时后端就推送 deposit 事件，不刷新页面也能看到"待确认"（E2E-2 已修复）
      await expect(page.getByText(/待确认 \d+\/19/)).toBeVisible();
      await e2e.mine(request, 'TRC20', 25);
      await expect(page.getByText('已到账 · TRC20')).toBeVisible();
      // 站内信推送的提示（AC-07 客户收到通知）
      await expect(page.getByText('充值已到账').first()).toBeVisible();
      await expect(page.locator('.assets-hero, .card').getByText('1,000.00').first()).toBeVisible();
      // S2：资产页显示三项（可用、下注中、提现冻结），还没有下注时下注中是 0
      await expect(page.locator('.overview').getByText('下注中')).toBeVisible();
      await expect(page.locator('.overview .ov-row > div').filter({ hasText: '下注中' }).locator('.num')).toHaveText('0.00');
    }, 90_000);

    await step('客户：提交 KYC（护照 + 3 张照片）', async () => {
      await page.goto('/me/kyc');
      await page.getByRole('radio', { name: '护照' }).click();
      await page.getByLabel('证件号').fill(`E2E${id}`.toUpperCase().slice(0, 12));
      for (const slot of ['front', 'back', 'selfie']) {
        await page.locator(`#kyc-${slot}`).setInputFiles({ name: `${slot}.png`, mimeType: 'image/png', buffer: PNG_1PX });
      }
      await page.getByRole('button', { name: '提交审核' }).click();
      await expect(page.getByText('审核中')).toBeVisible();
    });

    await step('后台：KYC 审核通过', async () => {
      await adminPage.goto('/admin/kyc');
      const row = adminPage.getByRole('row').filter({ hasText: email });
      await row.getByRole('link', { name: '审核' }).click();
      await expect(adminPage.getByText(email)).toBeVisible();
      await expect(adminPage.getByRole('img', { name: '证件正面' })).toBeVisible();
      await adminPage.getByRole('button', { name: cjk('通过') }).click();
      await confirmAndWait(adminPage, cjk('通过'), '/review');
      await expect(adminPage.locator('.ant-tag, .pill').getByText('已通过').first()).toBeVisible();
    });

    await step('客户：站内信有注册欢迎、充值到账、身份认证通过', async () => {
      await page.goto('/me/notifications');
      await expect(page.getByText(`欢迎来到 ${BRAND}`).first()).toBeVisible();
      await expect(page.getByText('充值已到账').first()).toBeVisible();
      await expect(page.getByText('身份认证已通过').first()).toBeVisible();
    });

    await step('客户：资金流水里有充值', async () => {
      await page.goto('/assets/ledger');
      const list = page.locator('.ledger');
      await expect(list.getByText(/^充值( · |$)/).first()).toBeVisible();
      await expectNoHorizontalScroll(page, '资金流水页');
    });

    await step('客户：加白名单地址（ERC20）', async () => {
      await page.goto('/assets/whitelist');
      await page.getByRole('button', { name: '添加地址' }).click();
      const dlg = page.getByRole('dialog');
      await dlg.getByRole('radio', { name: 'ERC20' }).click();
      await dlg.getByLabel('地址', { exact: true }).fill(WITHDRAW_TO);
      await dlg.getByLabel('备注名').fill('端到端测试钱包');
      await dlg.getByRole('button', { name: '确认添加' }).click();
      await expect(page.getByText('地址已添加')).toBeVisible();
      await expect(page.locator('.wl-list li').filter({ hasText: WITHDRAW_TO })).toContainText('已生效');
    });

    await step(`客户：提现 ${WITHDRAW} USDT`, async () => {
      await page.goto('/assets/withdraw');
      await expect(page.getByText('身份认证已通过')).toBeVisible();
      await page.getByLabel('提现金额').fill(WITHDRAW);
      await expectNoHorizontalScroll(page, '提现页');
      await page.getByRole('button', { name: '提交提现' }).click();
      await page.getByRole('button', { name: '确认提交' }).click();
      await expect(page.getByText('提现已提交')).toBeVisible();
      await expect(page.locator('.wd-recs li').first()).toContainText(/冷静期中|待审核/);
      // 冷静期为 0：立即跑一次定时推进（不用等 30 秒），进入待审核
      await e2e.advanceWithdrawals(request);
      await expect(page.locator('.wd-recs li').first()).toContainText('待审核');
    });

    let withdrawalId = '';
    await step('后台：出金审核通过（立即广播）', async () => {
      await adminPage.goto('/admin/funds/withdrawals');
      await adminPage.getByRole('row').filter({ hasText: email }).first().click();
      const detail = adminPage.getByRole('complementary', { name: '出金详情' });
      await expect(detail).toContainText(email);
      withdrawalId = ((await detail.locator('h3 .num').innerText()) || '').trim();
      expect(withdrawalId).toMatch(/^\d+$/);
      // v2 提现自动审批：总开关默认关闭，冷静期结束时照常复判并记录（详情有"规则判定"卡片），仍然人工审核
      await expect(detail.getByRole('region', { name: '规则判定' })).toContainText('总开关关闭，全部人工审核');
      await detail.getByRole('button', { name: cjk('通过') }).click();
      await confirmAndWait(adminPage, cjk('通过'), `/api/admin/withdrawals/${withdrawalId}/approve`);
      await expect(page.locator('.wd-recs li').first()).toContainText('上链中');
    });

    await step('链上确认：提现完成，客户看到已完成', async () => {
      await e2e.confirmWithdrawal(request, withdrawalId);
      await expect(page.locator('.wd-recs li').first()).toContainText('已完成');
      await page.goto('/assets/ledger');
      await expect(page.locator('.ledger').getByText(/^提现( · |$)/).first()).toBeVisible();
    });

    await step('后台：查看客户（KYC 已通过、有资金流水）', async () => {
      await adminPage.goto('/admin/customers');
      await adminPage.locator('#c-kw').fill(email);
      await adminPage.getByRole('button', { name: cjk('查询') }).click();
      const row = adminPage.getByRole('row').filter({ hasText: email });
      await expect(row).toHaveCount(1);
      await expect(row).toContainText('已通过');
      await row.getByRole('link', { name: '详情' }).click();
      await expect(adminPage.getByText(email).first()).toBeVisible();
      await expect(adminPage.getByText('最近资金流水')).toBeVisible();
    });

    await step('通知邮件：验证码、入金到账、KYC 结果、出金状态都发到客户邮箱', async () => {
      // 邮件任务每 2 秒发一次：等全部到齐（KYC 结果、到账通知、提现状态）；欢迎只发站内信，不发邮件
      for (const subject of [`${BRAND} 验证码`, '充值已到账', '身份认证已通过', '提现状态更新']) {
        await expect.poll(async () => (await e2e.mailSubjects(request, email)).join(' | '), { message: `等邮件「${subject}」`, timeout: 20_000 }).toContain(subject);
      }
      const subjects = (await e2e.mailSubjects(request, email)).join(' | ');
      test.info().annotations.push({ type: '客户收到的邮件', description: subjects });
      expect(subjects, '邮件标题里没有旧品牌名').not.toMatch(OLD_BRAND);
      expect(subjects, '欢迎只发站内信，不发邮件').not.toMatch(/欢迎来到/);
    });

    await step('客户收到的内容里没有内部字段、不依赖 Google', async () => {
      await rec.settle();
      await adminRec.settle();
      expect(rec.apiBodies.length, '记录到的客户接口返回').toBeGreaterThan(10);
      expect(rec.wsFrames.length, '记录到的客户推送').toBeGreaterThan(0);
      expect(rec.riskLeaks(), '客户收到的接口返回和推送里不能有内部字段').toEqual([]);
      expect([...rec.googleHosts(), ...adminRec.googleHosts()], '不能请求 Google 域名').toEqual([]);
      test.info().annotations.push({
        type: '记录',
        description: `客户接口返回 ${rec.apiBodies.length} 条、推送 ${rec.wsFrames.length} 条；访问的域名：${[...new Set([...rec.hosts, ...adminRec.hosts])].join(', ')}`,
      });
    });
  } finally {
    await client.close();
    await admin.close();
    await request.dispose();
  }
});
