/** 认证的真实接口：`/api/auth/*`、`/api/me/session`（`account/auth/AuthController.java`） */
import { ApiError, validCooldown } from '../errors';
import { get, post } from '../http';
import type { EmailCodePurpose, RegisterRequest, Session } from '../types';

interface SessionView {
  userId: string;
  email: string;
}

const toSession = (v: SessionView): Session => ({ userId: v.userId, email: v.email });

/** 后端的验证码用途是枚举 REGISTER / RESET_PASSWORD */
const PURPOSE: Record<EmailCodePurpose, string> = { register: 'REGISTER', resetPassword: 'RESET_PASSWORD' };

/** 旧版后端（返回 204、没有响应体）时的重发间隔，和后端默认的 `forecast.auth.code-resend-seconds` 一致 */
const DEFAULT_RESEND_SECONDS = 60;

export async function login(email: string, password: string): Promise<Session> {
  return toSession(await post<SessionView>('/api/auth/login', { email, password }, { quiet401: true, device: true }));
}

export async function register(req: RegisterRequest): Promise<Session> {
  // 勾选框在表单里叫 agree（"已年满 18 周岁并同意服务条款"，S11）
  return toSession(
    await post<SessionView>('/api/auth/register', req, { codeFields: { CODE_INVALID: 'code', EMAIL_TAKEN: 'email' }, fieldNames: { adultConfirmed: 'agree' }, device: true }),
  );
}

/**
 * 发验证码：后端返回 200 `{"cooldownSeconds":60}`，按它倒计时；兼容旧版的 204（没有响应体）按 60 秒。
 * 60 秒内重发是 429 `CODE_TOO_FREQUENT`，ApiError.cooldownSeconds 是还要等的秒数。
 */
export async function sendEmailCode(email: string, purpose: EmailCodePurpose): Promise<{ cooldownSeconds: number }> {
  const r = await post<{ cooldownSeconds?: number } | undefined>('/api/auth/email-code', { email, purpose: PURPOSE[purpose] });
  return { cooldownSeconds: validCooldown(r?.cooldownSeconds) ?? DEFAULT_RESEND_SECONDS };
}

export async function resetPassword(email: string, code: string, password: string): Promise<void> {
  await post<void>('/api/auth/password/reset', { email, code, password }, { codeFields: { CODE_INVALID: 'code' } });
}

export async function changePassword(current: string, password: string): Promise<void> {
  await post<void>(
    '/api/auth/password/change',
    { currentPassword: current, newPassword: password },
    { fieldNames: { currentPassword: 'current', newPassword: 'password' }, codeFields: { CURRENT_PASSWORD_WRONG: 'current' } },
  );
}

export async function logout(): Promise<void> {
  await post<void>('/api/auth/logout', undefined, { quiet401: true });
}

/** 打开页面时问后端"现在是谁"：Cookie 有效返回会话，没登录返回 null */
export async function restoreSession(): Promise<Session | null> {
  try {
    return toSession(await get<SessionView>('/api/me/session', { quiet401: true }));
  } catch (e) {
    if (e instanceof ApiError && e.reason === 'unauthorized') return null;
    throw e;
  }
}
