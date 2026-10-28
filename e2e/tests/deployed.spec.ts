/**
 * 只在部署形态（E2E_MODE=deployed，e2e/run-deployed.sh）运行：在真浏览器里确认 Nginx 镜像的路由、跳转、安全响应头和 Service Worker
 * （复制源的端到端测试方案第 9 节）。主流程（登录 Cookie 的 Secure、WebSocket 推送、测试环境标识条）在 main-flow.spec.ts 里。
 * 请求都从浏览器页面里发（同源 fetch），走的是浏览器的域名解析和 HTTPS，和真实用户一样。
 */
import { expect, test, type Page } from '@playwright/test';
import { ADMIN_URL, CLIENT_URL, DEPLOYED, PRELAUNCH_URL, SITE_URL } from './targets';

test.skip(!DEPLOYED, '只在部署形态运行（e2e/run-deployed.sh）');

/** 在页面里同源请求，返回状态码和内容类型（不跟随跳转） */
async function status(page: Page, path: string): Promise<{ status: number; type: string; location: string }> {
  return page.evaluate(async (p) => {
    const r = await fetch(p, { redirect: 'manual', cache: 'no-store' });
    return { status: r.type === 'opaqueredirect' ? 302 : r.status, type: r.headers.get('content-type') || '', location: r.headers.get('location') || '' };
  }, path);
}

test('Nginx：两个域名的路由、跳转和安全响应头', async ({ browser }) => {
  const ctx = await browser.newContext({ ignoreHTTPSErrors: true, serviceWorkers: 'block' });
  const page = await ctx.newPage();
  try {
    // http → https（80 只做跳转）
    await page.goto(CLIENT_URL.replace(/^https:/, 'http:') + '/');
    expect(page.url(), 'http 跳到 https').toBe(`${CLIENT_URL}/`);

    // 客户端域名
    const res = await page.goto(`${CLIENT_URL}/`);
    const h = res!.headers();
    expect(h['strict-transport-security'], 'HSTS').toContain('max-age=');
    expect(h['x-content-type-options']).toBe('nosniff');
    expect(h['x-frame-options']).toBe('DENY');
    const health = await page.evaluate(async () => (await fetch('/api/health', { cache: 'no-store' })).json());
    expect(health.status).toBe('UP');
    expect(health.env, '健康检查带测试环境标识').toBe('test');
    // 监控告警（v2 E8）：业务告警只在后台域名的健康检查里有；客户端域名只有存活信息，伪造 Nginx 内部头也拿不到
    expect(health.alert, '客户端域名不返回告警汇总').toBeUndefined();
    expect(health.alerts, '客户端域名不返回告警各项').toBeUndefined();
    const forgedHealth = await page.evaluate(async () =>
      (await fetch('/api/health', { cache: 'no-store', headers: { 'X-Fc-Health-Alerts': '1', 'X-Real-IP': '' } })).json());
    expect(forgedHealth.alerts, '客户端自己带 X-Fc-Health-Alerts 不起作用（Nginx 覆盖）').toBeUndefined();
    // 追踪号（v2 E9）：Nginx 生成 $request_id，用它覆盖客户端自己带的 X-Request-Id，后端沿用并放进响应头和错误响应体
    const trace = await page.evaluate(async () => {
      const a = await fetch('/api/health', { cache: 'no-store' });
      const b = await fetch('/api/health', { cache: 'no-store', headers: { 'X-Request-Id': 'client-forged-0001' } });
      const e = await fetch('/api/me/session', { cache: 'no-store' });
      return { a: a.headers.get('x-request-id'), b: b.headers.get('x-request-id'), e: e.headers.get('x-request-id'), body: await e.json() };
    });
    expect(trace.a, 'Nginx 的 $request_id（32 位十六进制）').toMatch(/^[0-9a-f]{32}$/);
    expect(trace.b, '客户端伪造的追踪号被 Nginx 覆盖').toMatch(/^[0-9a-f]{32}$/);
    expect(trace.b).not.toBe(trace.a);
    expect(trace.body.traceId, '错误响应体带同一个追踪号').toBe(trace.e);
    for (const p of ['/api/e2e/mail/code?email=x%40example.com', '/api/admin/me', '/actuator/health', '/actuator/prometheus']) {
      expect((await status(page, p)).status, `客户端域名 ${p} 应为 404（Nginx 不转发）`).toBe(404);
    }
    expect((await status(page, '/api/me/session')).status, '客户端接口经 Nginx 到后端（未登录 401）').toBe(401);
    // 浏览器路由：直接打开或刷新任意页面路径都回到客户端的 index.html。
    // 资产页路由 /assets* 和构建目录 assets/ 同名（曾经是 403 / 404，见方案第 9 节 D-1）
    for (const p of ['/markets', '/assets', '/assets/', '/assets/ledger', '/assets/withdraw', '/assets/whitelist', '/me/kyc']) {
      const r = await status(page, p);
      expect(r.status, `直接打开 ${p}`).toBe(200);
      expect(r.type, `直接打开 ${p} 是页面`).toContain('text/html');
    }
    // 构建出来的脚本：长期缓存；不存在的脚本是 404，不能拿页面冒充
    const js = await page.evaluate(() => (document.querySelector('script[type="module"][src]') as HTMLScriptElement | null)?.getAttribute('src') || '');
    expect(js).toMatch(/^\/assets\/.+\.js$/);
    const jsRes = await page.evaluate(async (u) => {
      const r = await fetch(u, { cache: 'no-store' });
      return { status: r.status, type: r.headers.get('content-type') || '', cache: r.headers.get('cache-control') || '' };
    }, js);
    expect(jsRes.status).toBe(200);
    expect(jsRes.type).toContain('javascript');
    expect(jsRes.cache).toContain('max-age=2592000');
    expect((await status(page, '/assets/not-exist-0000.js')).status, '不存在的脚本').toBe(404);
    expect((await status(page, '/')).type).toContain('text/html');

    // 后台域名
    await page.goto(`${ADMIN_URL}/`);
    expect(page.url(), '后台域名 / 跳到 /admin/').toMatch(/\/admin\/(login)?$/);
    expect((await status(page, '/api/health')).status).toBe(200);
    const adminHealth = await page.evaluate(async () => (await fetch('/api/health', { cache: 'no-store' })).json());
    expect(adminHealth.status).toBe('UP');
    expect(adminHealth.alert, '后台域名带告警汇总（不影响 status）').toMatch(/^(OK|ALERT)$/);
    expect(typeof adminHealth.alerts, '后台域名带告警各项').toBe('object');
    expect((await status(page, '/api/admin/me')).status, '后台接口经 Nginx 到后端（未登录 401）').toBe(401);
    for (const p of ['/api/me/session', '/api/e2e/mail/code?email=x%40example.com', '/actuator/health', '/actuator/prometheus']) {
      expect((await status(page, p)).status, `后台域名 ${p} 应为 404`).toBe(404);
    }
  } finally {
    await ctx.close();
  }
});

test('Nginx：官网域名只开放页面和健康检查', async ({ browser }) => {
  const ctx = await browser.newContext({ ignoreHTTPSErrors: true, serviceWorkers: 'block' });
  const page = await ctx.newPage();
  try {
    await page.goto(SITE_URL.replace(/^https:/, 'http:') + '/');
    expect(page.url(), '官网 http 跳到 https').toBe(`${SITE_URL}/`);
    const res = await page.goto(`${SITE_URL}/`);
    const h = res!.headers();
    expect(h['strict-transport-security'], 'HSTS').toContain('max-age=');
    expect(h['x-frame-options']).toBe('DENY');
    // 页面里的客户端地址由 Nginx 填成客户端域名（注册、登录跳过去）
    expect(await page.locator('meta[name="fc-client-url"]').getAttribute('content')).toBe(CLIENT_URL);
    expect((await status(page, '/api/health')).status).toBe(200);
    const siteHealth = await page.evaluate(async () =>
      (await fetch('/api/health', { cache: 'no-store', headers: { 'X-Fc-Health-Alerts': '1' } })).json());
    expect(siteHealth.status).toBe('UP');
    expect(siteHealth.alert, '官网的健康检查不带业务告警（伪造头也不行）').toBeUndefined();
    expect(siteHealth.alerts).toBeUndefined();
    for (const p of ['/api/me/session', '/api/public/quotes', '/api/health;x', '/api/admin/me', '/api/e2e/mail/code?email=x%40example.com', '/actuator/health']) {
      expect((await status(page, p)).status, `官网域名 ${p} 应为 404`).toBe(404);
    }
    for (const p of ['/terms', '/privacy', '/risk', '/terms/']) {
      const r = await status(page, p);
      expect(r.status, `直接打开 ${p}`).toBe(200);
      expect(r.type).toContain('text/html');
    }
    expect((await status(page, '/assets/not-exist-0000.js')).status, '不存在的脚本').toBe(404);
  } finally {
    await ctx.close();
  }
});

test('Nginx：对外预热域名（S18，P1）根路径是等候页、没有测试环境标识、只开放等候页用到的接口', async ({ browser }) => {
  test.skip(!PRELAUNCH_URL, '没有配对外预热域名（E2E_PRELAUNCH_URL）');
  const ctx = await browser.newContext({ ignoreHTTPSErrors: true, serviceWorkers: 'block', locale: 'zh-TW' });
  const page = await ctx.newPage();
  try {
    await page.goto(PRELAUNCH_URL.replace(/^https:/, 'http:') + '/');
    expect(page.url(), '预热域名 http 跳到 https').toBe(`${PRELAUNCH_URL}/`);
    const res = await page.goto(`${PRELAUNCH_URL}/?src=line&cmp=seed`);
    const h = res!.headers();
    expect(h['strict-transport-security'], 'HSTS').toContain('max-age=');
    expect(h['x-frame-options']).toBe('DENY');
    expect(h['x-content-type-options']).toBe('nosniff');
    // 页面：服务器填的站点形态、SSI 的标题和 canonical（指向预热域名自己）、分享图；可以收录
    expect(await page.locator('meta[name="fc-site-mode"]').getAttribute('content')).toBe('prelaunch');
    expect(await page.locator('link[rel="canonical"]').getAttribute('href')).toBe(`${PRELAUNCH_URL}/`);
    expect(await page.locator('meta[property="og:image"]').getAttribute('content')).toBe(`${PRELAUNCH_URL}/og-prelaunch.png`);
    expect(await page.locator('meta[name="robots"]').count(), '根路径允许收录').toBe(0);
    // 渲染：等候页（繁中），没有测试环境标识，也不留空白；没有注册、登录
    await expect(page.getByTestId('prelaunch')).toBeVisible();
    await expect(page.getByRole('heading', { level: 1 })).toContainText('即將上線');
    await expect(page.getByTestId('wl-submit')).toBeVisible();
    await expect(page.getByTestId('tw-deposit'), '繁中、上线前有台湾入金教程').toBeVisible();
    await page.waitForTimeout(1500);
    await expect(page.getByTestId('env-banner')).toHaveCount(0);
    expect(await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--banner-h').trim()), '顶部不留空白').toBe('0px');
    await expect(page.getByTestId('guest-login')).toHaveCount(0);
    const health = await page.evaluate(async () => (await fetch('/api/health', { cache: 'no-store' })).json());
    expect(health.status).toBe('UP');
    expect(health.env, '预热域名的健康检查不带 env').toBeUndefined();
    expect(health.alerts).toBeUndefined();
    // 来源参数照样记（P4：LINE 内测群的链接）
    expect(await page.evaluate(() => localStorage.getItem('fc.attr') || '')).toContain('line');
    // 接口：只放行等候页用到的
    expect((await status(page, '/api/public/board')).status).toBe(200);
    expect((await status(page, '/api/public/pages/how-to-play?lang=zh-TW')).status).toBe(200);
    for (const p of ['/api/me/session', '/api/markets', '/api/auth/login', '/api/admin/me', '/api/e2e/mail/code?email=x%40example.com', '/api/health;x',
      '/api/public/board;x', '/api/public/seo/head', '/actuator/health', '/api/ws/markets']) {
      expect((await status(page, p)).status, `预热域名 ${p} 应为 404`).toBe(404);
    }
    // 页面路径：注册、登录、市场、资产、分享链接都跳回根路径；/data 301 回根路径（参数保留）；确认链接照常是页面
    for (const p of ['/login', '/register', '/markets', '/markets/1', '/assets', '/me', '/m/1', '/how-to-play', '/admin/']) {
      const r = await status(page, p);
      expect(r.status, `${p} 跳回根路径`).toBe(302);
    }
    const plain = await page.request.get(`${PRELAUNCH_URL}/data?src=x`, { maxRedirects: 0 });
    expect(plain.status()).toBe(301);
    expect(plain.headers()['location']).toMatch(/\/\?src=x$/);
    const login = await page.request.get(`${PRELAUNCH_URL}/login`, { maxRedirects: 0 });
    expect(login.headers()['location']).toMatch(/\/$/);
    const confirm = await status(page, '/data/confirm?t=0123456789abcdef0123456789abcdef');
    expect(confirm.status).toBe(200);
    expect(confirm.type).toContain('text/html');
    // robots.txt、sitemap.xml：预热域名自己的，允许收录根路径
    const robots = await (await page.request.get(`${PRELAUNCH_URL}/robots.txt`)).text();
    expect(robots).toContain(`Sitemap: ${PRELAUNCH_URL}/sitemap.xml`);
    expect(robots).toContain('Allow: /$');
    const sitemap = await (await page.request.get(`${PRELAUNCH_URL}/sitemap.xml`)).text();
    expect(sitemap).toContain(`<loc>${PRELAUNCH_URL}/</loc>`);
    expect(sitemap).toContain(`hreflang="zh-TW" href="${PRELAUNCH_URL}/?lang=zh-TW"`);
    expect((await page.request.get(`${PRELAUNCH_URL}/og-prelaunch.png`)).headers()['content-type']).toContain('image/png');
    expect((await status(page, '/assets/not-exist-0000.js')).status).toBe(404);

    // 其他三个测试域名照常显示测试环境标识（客户端域名）
    await page.goto(`${CLIENT_URL}/data`);
    await expect(page.getByTestId('env-banner')).toBeVisible();
    expect(await page.locator('meta[name="fc-site-mode"]').getAttribute('content'), '客户端域名不填站点形态').toBe('__FC_SITE_MODE__');
  } finally {
    await ctx.close();
  }
});

test('Service Worker：经 HTTPS 注册、接管页面，不接管 /api 和 /admin', async ({ browser }) => {
  // 这里允许 Service Worker（主流程为了记录请求把它关掉了）
  const ctx = await browser.newContext({ ignoreHTTPSErrors: true, serviceWorkers: 'allow' });
  const page = await ctx.newPage();
  try {
    await page.goto(`${CLIENT_URL}/`);
    const script = await page.evaluate(async () => {
      const reg = await navigator.serviceWorker.ready;
      return reg.active?.scriptURL || '';
    });
    expect(script, 'Service Worker 已激活').toBe(`${CLIENT_URL}/sw.js`);
    // 重新打开页面后由 Service Worker 接管
    await page.reload();
    await expect.poll(() => page.evaluate(() => !!navigator.serviceWorker.controller), { message: '页面由 Service Worker 接管' }).toBe(true);
    // Service Worker 用 importScripts 加载推送处理脚本（push-sw.js）：要能取到、类型是 JavaScript，否则注册会失败
    const push = await status(page, '/push-sw.js');
    expect(push.status).toBe(200);
    expect(push.type).toContain('javascript');
    // 被接管后：/api/ 的页面导航不能被换成客户端的 index.html（navigateFallbackDenylist）
    const api = await page.goto(`${CLIENT_URL}/api/health`);
    expect(api!.headers()['content-type'], '/api/health 仍由后端返回 JSON').toContain('application/json');
    await page.goto(`${CLIENT_URL}/`);
    // 页面能正常显示（Service Worker 的缓存页面和最新构建一致）
    await expect(page.getByTestId('env-banner')).toBeVisible();
  } finally {
    await ctx.close();
  }
});
