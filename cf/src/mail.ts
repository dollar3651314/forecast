/**
 * 发邮件（forecast S19，P12）：Resend 的 HTTP 接口（https://resend.com/docs/api-reference/emails/send-email），不用 SMTP——
 * Workers 只能用 connect() 自己实现 SMTP 和 STARTTLS，HTTP 接口一次请求就发，错误码清楚（403 = key 没有这个发信域名的权限，C104）。
 * - key 是 Worker 的密钥 RESEND_API_KEY（Amos 在控制台填，K2）；没填时不发、返回 not_configured（"未开通"，不是故障，C103）。
 * - 超时 10 秒；网络错误、5xx、429 再试一次；日志里邮箱打码，不打印 key。
 */
import type { Env } from './env';
import { CONFIRM_MAIL, render, type Lang } from './mailTexts';
import { mask } from './util';

export const RESEND_URL = 'https://api.resend.com/emails';
export const DEFAULT_FROM = 'Quick Come <noreply@fc-email.quickcome.online>';

export type MailResult = 'sent' | 'not_configured' | 'failed';

export async function sendConfirmMail(env: Env, to: string, lang: Lang, confirmUrl: string, unsubUrl: string): Promise<MailResult> {
  const t = CONFIRM_MAIL[lang];
  const text = render(t.body, { confirm: confirmUrl, unsubscribe: unsubUrl });
  return send(env, { to, subject: t.subject, text, unsubscribeUrl: unsubUrl });
}

export async function send(env: Env, m: { to: string; subject: string; text: string; unsubscribeUrl?: string }): Promise<MailResult> {
  const key = env.RESEND_API_KEY?.trim();
  if (!key) {
    console.log(JSON.stringify({ event: 'mail.not_configured', to: mask(m.to) }));
    return 'not_configured';
  }
  const payload = {
    from: env.MAIL_FROM?.trim() || DEFAULT_FROM,
    to: [m.to],
    subject: m.subject,
    text: m.text,
    headers: m.unsubscribeUrl ? { 'List-Unsubscribe': `<${m.unsubscribeUrl}>` } : undefined,
  };
  for (let attempt = 1; attempt <= 2; attempt++) {
    let status = 0;
    try {
      const res = await fetch(RESEND_URL, {
        method: 'POST',
        headers: { Authorization: `Bearer ${key}`, 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
        signal: AbortSignal.timeout(10_000),
      });
      status = res.status;
      if (res.ok) {
        console.log(JSON.stringify({ event: 'mail.sent', to: mask(m.to) }));
        return 'sent';
      }
      const body = (await res.text()).slice(0, 300);
      console.log(JSON.stringify({ event: 'mail.failed', to: mask(m.to), status, attempt, body }));
      if (status < 500 && status !== 429) return 'failed';
    } catch (e) {
      console.log(JSON.stringify({ event: 'mail.failed', to: mask(m.to), attempt, error: String((e as Error)?.name || e) }));
    }
  }
  return 'failed';
}
