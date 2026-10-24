/**
 * 认证接口。
 * - real：POST /api/auth/*，令牌只在 HttpOnly Cookie 里，前端不保存；打开页面时用 GET /api/me/session 判断是否已登录。
 * - mock（原型）：令牌放在 localStorage，只为了刷新页面后保持登录。
 */
import * as mock from '../mocks/user';
import { IS_MOCK } from './mode';
import * as real from './real/auth';
import { setLoggedIn } from './real/session';
import type { EmailCodePurpose, RegisterRequest, Session } from './types';
import { unsubscribeThisDevice } from './webPush';

const SESSION_KEY = 'fc.session';

/** 原型：刷新页面后从 localStorage 恢复；真实环境不保存，始终返回 null（用 restoreSession 问后端） */
export function getSession(): Session | null {
  if (!IS_MOCK) return null;
  try {
    const raw = localStorage.getItem(SESSION_KEY);
    return raw ? (JSON.parse(raw) as Session) : null;
  } catch {
    return null;
  }
}

function saveSession(s: Session | null): void {
  if (!IS_MOCK) return;
  try {
    if (s) localStorage.setItem(SESSION_KEY, JSON.stringify(s));
    else localStorage.removeItem(SESSION_KEY);
  } catch {
    /* 忽略 */
  }
}

/** 打开页面时判断是否已登录：真实环境问后端（Cookie 是否有效），原型读 localStorage */
export async function restoreSession(): Promise<Session | null> {
  return IS_MOCK ? getSession() : real.restoreSession();
}

/** 登录状态变化（AuthProvider 调用）：真实环境据此连接或断开实时推送 */
export function sessionChanged(loggedIn: boolean): void {
  if (!IS_MOCK) setLoggedIn(loggedIn);
}

export async function login(email: string, password: string): Promise<Session> {
  const s = IS_MOCK ? await mock.login(email.trim(), password) : await real.login(email.trim(), password);
  saveSession(s);
  return s;
}

export async function register(req: RegisterRequest): Promise<Session> {
  const s = IS_MOCK ? await mock.register(req) : await real.register(req);
  saveSession(s);
  return s;
}

export const sendEmailCode = (email: string, purpose: EmailCodePurpose): Promise<{ cooldownSeconds: number }> =>
  IS_MOCK ? mock.sendEmailCode(email.trim(), purpose) : real.sendEmailCode(email.trim(), purpose);

export const resetPassword = (email: string, code: string, password: string): Promise<void> =>
  IS_MOCK ? mock.resetPassword(email.trim(), code.trim(), password) : real.resetPassword(email.trim(), code.trim(), password);

export const changePassword = (current: string, password: string): Promise<void> => (IS_MOCK ? mock.changePassword(current, password) : real.changePassword(current, password));

export async function logout(): Promise<void> {
  try {
    if (IS_MOCK) await mock.logout();
    else {
      // 先取消这台设备的推送订阅（需要登录态），再退出
      await unsubscribeThisDevice();
      await real.logout();
    }
  } finally {
    saveSession(null);
  }
}

/** 原型：邮件验证码固定值，页面上会提示（真实环境不显示） */
export const PROTOTYPE_EMAIL_CODE = mock.MOCK_EMAIL_CODE;
