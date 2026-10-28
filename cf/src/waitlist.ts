/**
 * 等候页（forecast S19，P12）：和测试服务器上 S16 的 growth/waitlist/WaitlistService 行为一样，接口、参数、返回、错误码也一样（页面不用改）。
 * - 留邮箱：双重确认（只发确认邮件，点链接后在页面上按"确认订阅"才算）；回答永远是 {"status":"check_email"}，不透露邮箱是否已在名单；
 *   发信放在回答之后（ctx.waitUntil），新老邮箱的回答时间也一样。
 * - 防刷：陷阱字段 website 有值时照样回答、什么都不做；同一 IP 每小时 10 次（陷阱字段也算）；同一邮箱确认邮件至少隔 10 分钟、最多 5 封。
 * - 确认：随机串不对 404；超过 7 天 410 WAITLIST_LINK_EXPIRED；等候页关着 409 WAITLIST_CLOSED；重复确认没有效果。
 * - 退订：不用登录，任何状态都能退订，重复也成功。
 * 和 S16 不同的地方：没有"上线通知"（上线通知由后端 S16 发，名单在开放交易时导过去，P15）；"关闭等候页"是变量 WAITLIST_OPEN。
 * 状态都用带旧状态的条件更新，同时两次请求只有一个生效。
 */
import { validateEmail } from '../../web/client/src/lib/emailRule';
import { fromBody } from './attribution';
import type { Env } from './env';
import { clientIp, error, json, readJson, str } from './http';
import { sendConfirmMail } from './mail';
import { hit } from './ratelimit';
import { flag, iso, normLang, sha256, token, TOKEN_RE } from './util';

export const RULES = {
  ipPerHour: 10,
  resendAfterMs: 10 * 60 * 1000,
  maxConfirmSends: 5,
  confirmValidMs: 7 * 24 * 60 * 60 * 1000,
};

export interface Row {
  id: number;
  email: string;
  lang: 'zh-TW' | 'zh-CN' | 'en';
  status: 'PENDING' | 'CONFIRMED' | 'UNSUBSCRIBED';
  confirm_token: string;
  confirm_sent_at: string;
  confirm_sends: number;
  unsub_token: string;
}

const CLOSED = () => error(409, 'WAITLIST_CLOSED', '等候页已经关闭，不再收邮箱');

export function waitlistOpen(env: Env): boolean {
  return flag(env.WAITLIST_OPEN, true);
}

function base(env: Env, req: Request): string {
  const b = env.PUBLIC_BASE_URL?.trim();
  return (b && /^https?:\/\/[^\s"'<>]+$/.test(b) ? b : new URL(req.url).origin).replace(/\/+$/, '');
}

/** 字段错误的代码和后端一样：REQUIRED / EMAIL_INVALID（客户端 api/errors.ts 的 FIELD_CODES） */
function emailError(raw: unknown): string | null {
  if (typeof raw !== 'string') return 'REQUIRED';
  const e = validateEmail(raw);
  if (e.length === 0) return null;
  return e[0] === 'validation.required' ? 'REQUIRED' : 'EMAIL_INVALID';
}

export async function subscribe(req: Request, env: Env, ctx: ExecutionContext, now = Date.now()): Promise<Response> {
  const r = await readJson(req);
  if (!r.ok) return r.res;
  const b = r.body;
  const fe = emailError(b.email);
  if (fe) return error(400, 'VALIDATION_FAILED', '邮箱格式不对', [{ field: 'email', code: fe }]);
  if (!waitlistOpen(env)) return CLOSED();
  const ipHash = await sha256(clientIp(req));
  const lim = await hit(env.DB, 'waitlist', ipHash, RULES.ipPerHour, 60 * 60 * 1000, now);
  if (!lim.ok) {
    return error(429, 'RATE_LIMITED', '请求太频繁，请稍后再试', [], { cooldownSeconds: lim.retryAfter }, { 'Retry-After': String(lim.retryAfter) });
  }
  const done = json({ status: 'check_email' });
  const website = str(b.website);
  if (website && website.trim() !== '') {
    console.log(JSON.stringify({ event: 'waitlist.honeypot' }));
    return done;
  }
  const email = (b.email as string).trim().toLowerCase();
  const lang = normLang(b.lang);
  const a = fromBody(b.attribution);
  const site = base(env, req);
  const t = iso(now);
  const confirm = token();
  const unsub = token();
  const ins = await env.DB.prepare(
    'INSERT INTO waitlist (email, lang, status, confirm_token, confirm_sent_at, confirm_sends, unsub_token, src, cmp, ref, start_param, ip_hash, created_at, updated_at) ' +
      "VALUES (?1, ?2, 'PENDING', ?3, ?4, 1, ?5, ?6, ?7, ?8, ?9, ?10, ?4, ?4) ON CONFLICT (email) DO NOTHING",
  )
    .bind(email, lang, confirm, t, unsub, a.src, a.cmp, a.ref, a.start, ipHash)
    .run();
  let mail: [string, string] | null = null;
  if (ins.meta.changes === 1) {
    mail = [confirm, unsub];
  } else {
    const row = await env.DB.prepare('SELECT * FROM waitlist WHERE email = ?1').bind(email).first<Row>();
    if (row) {
      const waited = now >= Date.parse(row.confirm_sent_at) + RULES.resendAfterMs;
      if (row.status === 'PENDING') {
        // 再发一封（同一个确认链接）；没到时间或者发够了就不发，回答一样。带旧的发送次数做条件更新，同时两次提交只发一封
        if (waited && row.confirm_sends < RULES.maxConfirmSends) {
          const u = await env.DB.prepare(
            "UPDATE waitlist SET confirm_sent_at = ?1, confirm_sends = confirm_sends + 1, lang = ?2, updated_at = ?1 WHERE id = ?3 AND status = 'PENDING' AND confirm_sends = ?4",
          )
            .bind(t, lang, row.id, row.confirm_sends)
            .run();
          if (u.meta.changes === 1) mail = [row.confirm_token, row.unsub_token];
        }
      } else if (row.status === 'UNSUBSCRIBED') {
        // 退订过又来留：重新走一次确认（新的确认链接）
        if (waited) {
          const u = await env.DB.prepare(
            "UPDATE waitlist SET status = 'PENDING', confirm_token = ?1, confirm_sent_at = ?2, confirm_sends = 1, lang = ?3, src = ?4, cmp = ?5, ref = ?6, start_param = ?7, " +
              "confirmed_at = NULL, unsubscribed_at = NULL, updated_at = ?2 WHERE id = ?8 AND status = 'UNSUBSCRIBED'",
          )
            .bind(confirm, t, lang, a.src, a.cmp, a.ref, a.start, row.id)
            .run();
          if (u.meta.changes === 1) mail = [confirm, row.unsub_token];
        }
      }
      // 已确认：什么都不发
    }
  }
  if (mail) {
    const [c, u] = mail;
    ctx.waitUntil(sendConfirmMail(env, email, lang, `${site}/data/confirm?t=${c}`, `${site}/data/unsubscribe?t=${u}`));
  }
  return done;
}

async function byToken(env: Env, col: 'confirm_token' | 'unsub_token', raw: unknown): Promise<Row | null> {
  const t = typeof raw === 'string' ? raw.trim().toLowerCase() : '';
  if (!TOKEN_RE.test(t)) return null;
  return env.DB.prepare(`SELECT * FROM waitlist WHERE ${col} = ?1`).bind(t).first<Row>();
}

const INVALID = () => error(404, 'NOT_FOUND', '链接无效');

export async function confirm(req: Request, env: Env, now = Date.now()): Promise<Response> {
  const r = await readJson(req);
  if (!r.ok) return r.res;
  const row = await byToken(env, 'confirm_token', r.body.token);
  if (!row) return INVALID();
  if (row.status === 'PENDING') {
    if (!waitlistOpen(env)) return CLOSED();
    // 恰好满 7 天的那一毫秒还有效，之后才算过期（和后端 now.isAfter(sentAt + 7 天) 一样，C106）
    if (now > Date.parse(row.confirm_sent_at) + RULES.confirmValidMs) return error(410, 'WAITLIST_LINK_EXPIRED', '确认链接已过期，请重新留一次邮箱');
    await env.DB.prepare("UPDATE waitlist SET status = 'CONFIRMED', confirmed_at = ?1, updated_at = ?1 WHERE id = ?2 AND status = 'PENDING'")
      .bind(iso(now), row.id)
      .run();
  }
  return linkResult(env, row.id);
}

export async function unsubscribe(req: Request, env: Env, now = Date.now()): Promise<Response> {
  const r = await readJson(req);
  if (!r.ok) return r.res;
  const row = await byToken(env, 'unsub_token', r.body.token);
  if (!row) return INVALID();
  if (row.status !== 'UNSUBSCRIBED') {
    await env.DB.prepare("UPDATE waitlist SET status = 'UNSUBSCRIBED', unsubscribed_at = ?1, updated_at = ?1 WHERE id = ?2 AND status <> 'UNSUBSCRIBED'")
      .bind(iso(now), row.id)
      .run();
  }
  return linkResult(env, row.id);
}

async function linkResult(env: Env, id: number): Promise<Response> {
  const after = await env.DB.prepare('SELECT status, lang FROM waitlist WHERE id = ?1').bind(id).first<{ status: string; lang: string }>();
  return json({ status: (after?.status ?? 'UNSUBSCRIBED').toLowerCase(), lang: after?.lang ?? 'en' });
}
