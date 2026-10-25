/**
 * 接口级端到端测试的公共部分（forecast S3 市场、S4 下注）：登录、注册、取登录 Cookie、读 JSON。
 * 两种形态都能用：默认形态经 server.mjs 转发到后端；部署形态直接访问后端 127.0.0.1:8080（和 /api/e2e/** 一样，见 targets.ts），
 * 部署形态的登录 Cookie 带 Secure，测试进程走的是 http，所以 Cookie 由这里取出来放进请求头。
 */
import { expect, request, type APIRequestContext, type APIResponse } from '@playwright/test';
import { STUB_URL } from './targets';
import { ADMIN_INIT_PASSWORD, ADMIN_NEW_PASSWORD, ADMIN_USERNAME, CLIENT_PASSWORD, e2e, uniqueId } from './support';

/** 取响应里的登录 Cookie（name=value） */
export function cookieOf(r: APIResponse, name: string): string {
  const c = r.headersArray().find((h) => h.name.toLowerCase() === 'set-cookie' && h.value.startsWith(`${name}=`));
  if (!c) throw new Error(`响应里没有 ${name}：${r.status()}`);
  return c.value.split(';')[0];
}

export async function json<T>(r: APIResponse, what: string): Promise<T> {
  const text = await r.text();
  expect(r.ok(), `${what} 返回 ${r.status()}：${text}`).toBe(true);
  return (text ? JSON.parse(text) : undefined) as T;
}

/** 带登录 Cookie 的请求上下文（新上下文，不带别的 Cookie） */
export function withCookie(cookie: string): Promise<APIRequestContext> {
  return request.newContext({ baseURL: STUB_URL, extraHTTPHeaders: { Cookie: cookie } });
}

/** 超管登录；新库第一次用初始密码登录要先改密码（和 admin.ts 的页面登录同一个规则） */
export async function adminSession(anon: APIRequestContext): Promise<APIRequestContext> {
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

/** 注册一个客户（验证码从 e2e 邮件替身取），返回带登录 Cookie 的请求上下文和 Cookie 本身（WebSocket 握手用） */
export async function clientSession(anon: APIRequestContext, prefix = 'mk'): Promise<{ api: APIRequestContext; cookie: string }> {
  const email = `${prefix}_${uniqueId()}@example.com`;
  await json(await anon.post('/api/auth/email-code', { data: { email, purpose: 'REGISTER' } }), '发验证码');
  const code = await e2e.mailCode(anon, email);
  const r = await anon.post('/api/auth/register', {
    data: { email, code, password: CLIENT_PASSWORD, firstName: 'Market', lastName: 'Tester', birthDate: '1990-01-27', nationality: 'SG' },
  });
  await json(r, '注册');
  const cookie = cookieOf(r, 'fc_session');
  return { api: await withCookie(cookie), cookie };
}

/** 三种语言的测试文字 */
export const t = (s: string) => ({ en: `${s} (en)`, zhCN: `${s}（简）`, zhTW: `${s}（繁）` });
