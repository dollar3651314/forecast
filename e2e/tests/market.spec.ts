/**
 * 市场（forecast S3）的接口级端到端测试：真实后端（profile e2e）+ 可调时钟。页面在原型确认后做（S11），这里只走接口：
 * 后台建草稿 → 上架 → 客户端列表和详情看到 → 改费率（修改记录公开）→ 时钟拨到截止时间 → 截止任务跑一轮 → 没有下注（S3 还没有下注，按 E4）作废。
 * 两种形态都跑：默认形态经 server.mjs 转发到后端；部署形态直接访问后端 127.0.0.1:8080（和 /api/e2e/** 一样，见 targets.ts），
 * 部署形态的登录 Cookie 带 Secure，测试进程走的是 http，所以 Cookie 由这里取出来放进请求头。
 */
import { expect, request, test, type APIRequestContext, type APIResponse } from '@playwright/test';
import { STUB_URL } from './targets';
import { ADMIN_INIT_PASSWORD, ADMIN_NEW_PASSWORD, ADMIN_USERNAME, CLIENT_PASSWORD, e2e, step, uniqueId } from './support';

/** 取响应里的登录 Cookie（name=value） */
function cookieOf(r: APIResponse, name: string): string {
  const c = r.headersArray().find((h) => h.name.toLowerCase() === 'set-cookie' && h.value.startsWith(`${name}=`));
  if (!c) throw new Error(`响应里没有 ${name}：${r.status()}`);
  return c.value.split(';')[0];
}

async function json<T>(r: APIResponse, what: string): Promise<T> {
  const text = await r.text();
  expect(r.ok(), `${what} 返回 ${r.status()}：${text}`).toBe(true);
  return (text ? JSON.parse(text) : undefined) as T;
}

/** 带登录 Cookie 的请求上下文（新上下文，不带别的 Cookie） */
function withCookie(cookie: string): Promise<APIRequestContext> {
  return request.newContext({ baseURL: STUB_URL, extraHTTPHeaders: { Cookie: cookie } });
}

/** 超管登录；新库第一次用初始密码登录要先改密码（和 admin.ts 的页面登录同一个规则） */
async function adminSession(anon: APIRequestContext): Promise<APIRequestContext> {
  let r = await anon.post('/api/admin/auth/login', { data: { username: ADMIN_USERNAME, password: ADMIN_NEW_PASSWORD } });
  if (r.ok()) return withCookie(cookieOf(r, 'fc_admin_session'));
  r = await anon.post('/api/admin/auth/login', { data: { username: ADMIN_USERNAME, password: ADMIN_INIT_PASSWORD } });
  await json(r, '超管登录');
  const first = await withCookie(cookieOf(r, 'fc_admin_session'));
  const changed = await first.post('/api/admin/auth/change-password', { data: { oldPassword: ADMIN_INIT_PASSWORD, newPassword: ADMIN_NEW_PASSWORD } });
  await json(changed, '首次登录改密码');
  await first.dispose();
  return withCookie(cookieOf(changed, 'fc_admin_session'));
}

/** 注册一个客户（验证码从 e2e 邮件替身取） */
async function clientSession(anon: APIRequestContext): Promise<APIRequestContext> {
  const email = `mk_${uniqueId()}@example.com`;
  await json(await anon.post('/api/auth/email-code', { data: { email, purpose: 'REGISTER' } }), '发验证码');
  const code = await e2e.mailCode(anon, email);
  const r = await anon.post('/api/auth/register', {
    data: { email, code, password: CLIENT_PASSWORD, firstName: 'Market', lastName: 'Tester', birthDate: '1990-01-27', nationality: 'SG' },
  });
  await json(r, '注册');
  return withCookie(cookieOf(r, 'fc_session'));
}

type Detail = { publicNo: string; status: string; feeRate: string; voidReason: string | null; feeChanges: { oldRate: string; newRate: string; time: string }[] };

test('市场：上架 → 客户端可见 → 改费率公开 → 到点截止（可调时钟）', async () => {
  const anon = await request.newContext({ baseURL: STUB_URL });
  try {
    const admin = await step('超管登录', () => adminSession(anon));
    const client = await step('注册客户', () => clientSession(anon), 90_000);
    const now = await step('读可调时钟', async () => new Date((await json<{ now: string }>(await anon.get('/api/e2e/clock'), '读时钟')).now));
    const at = (ms: number) => new Date(now.getTime() + ms).toISOString();
    const t = (s: string) => ({ en: `${s} (en)`, zhCN: `${s}（简）`, zhTW: `${s}（繁）` });

    const no = await step('后台建草稿并上架', async () => {
      const draft = await json<{ publicNo: string; feeRate: string; missing: unknown[] }>(
        await admin.post('/api/admin/markets', {
          data: {
            category: 'MACRO',
            title: t('端到端测试市场'),
            rules: t('按官方公布为准；无法判定的作废退款'),
            resolutionMode: 'PLATFORM',
            sourcePrimary: 'example.org',
            sourceBackup: 'example.net',
            openAt: at(-60_000),
            closeAt: at(120_000),
            eventStartAt: at(120_000),
            eventEndAt: at(180_000),
            options: [
              { label: t('是'), winCondition: t('公布为是') },
              { label: t('否'), winCondition: t('公布为否') },
            ],
          },
        }),
        '建草稿',
      );
      expect(draft.feeRate, '新建按品类默认 5%').toBe('5');
      expect(draft.missing).toEqual([]);
      const listed = await json<{ status: string }>(await admin.post(`/api/admin/markets/${draft.publicNo}/publish`, { data: { checked: true } }), '上架');
      expect(listed.status).toBe('listed');
      return draft.publicNo;
    });

    await step('客户端列表和详情看到它', async () => {
      const page = await json<{ items: { publicNo: string; status: string }[] }>(await client.get('/api/markets?status=active&sort=newest&pageSize=50'), '市场列表');
      expect(page.items.map((i) => i.publicNo)).toContain(no);
      const d = await json<Detail>(await client.get(`/api/markets/${no}`), '市场详情');
      expect(d.status).toBe('listed');
    });

    await step('改费率 5% → 8%，修改记录公开', async () => {
      const r = await json<{ oldRate: string; newRate: string }>(await admin.put(`/api/admin/markets/${no}/fee`, { data: { feeRate: '8', reason: '端到端测试' } }), '改费率');
      expect(`${r.oldRate}>${r.newRate}`).toBe('5>8');
      const d = await json<Detail>(await client.get(`/api/markets/${no}`), '市场详情');
      expect(d.feeRate).toBe('8');
      expect(d.feeChanges.map((c) => `${c.oldRate}>${c.newRate}`)).toEqual(['5>8']);
    });

    await step('时钟拨到截止时间，截止任务跑一轮：没有下注，按 E4 作废', async () => {
      await json(await anon.post('/api/e2e/clock/advance', { data: { millis: 120_000 } }), '拨时钟');
      await json(await anon.post('/api/e2e/markets/tick'), '跑截止任务');
      await expect
        .poll(async () => (await json<Detail>(await client.get(`/api/markets/${no}`), '市场详情')).status, { message: '等市场截止', timeout: 20_000 })
        .toBe('voided');
      const d = await json<Detail>(await client.get(`/api/markets/${no}`), '市场详情');
      expect(d.voidReason).toBe('NOT_ENOUGH_OPTIONS');
      const after = await admin.put(`/api/admin/markets/${no}/fee`, { data: { feeRate: '6', reason: '作废后' } });
      expect(after.status(), '终点状态不能再改费率').toBe(409);
    });
    await admin.dispose();
    await client.dispose();
  } finally {
    await anon.post('/api/e2e/clock/reset');
    await anon.dispose();
  }
});
