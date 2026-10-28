/**
 * 等候页（forecast S19，P12）：和 S16 的 S16WaitlistTest 同样的行为——双重确认、陷阱字段、按 IP 限次、重发间隔和上限、
 * 不透露邮箱是否已在名单、确认（无效 404、过期 410、关闭 409、重复没有效果）、免登录退订、退订后再留要重新确认、三种语言的邮件。
 * 真实的 D1（Miniflare 本地实现），时间用参数传进去（可调时钟，C106），每个用例自己的邮箱和 IP（C80）。
 */
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { CONFIRM_MAIL } from '../src/mailTexts';
import { confirm, RULES, subscribe, unsubscribe } from '../src/waitlist';
import { CF_DIR, freshIp, links, newDb, post, stubFetch, testCtx, testEnv, tokenFrom } from './helpers';

let dispose: () => Promise<void>;
let db: D1Database;
beforeAll(async () => {
  ({ dispose, db } = await newDb());
});
afterAll(async () => {
  await dispose();
});
afterEach(() => {
  vi.unstubAllGlobals();
});

const T0 = Date.parse('2026-10-28T08:00:00.000Z');
let seq = 0;
const email = (tag: string) => `wl-${tag}-${++seq}@Example.com`;

async function sub(e: string, opts: { lang?: string; ip?: string; website?: string; attribution?: unknown; now?: number; env?: ReturnType<typeof testEnv> } = {}) {
  const ctx = testCtx();
  const res = await subscribe(
    post('/api/public/waitlist', { email: e, lang: opts.lang ?? 'zh-TW', website: opts.website ?? '', attribution: opts.attribution }, opts.ip ?? freshIp()),
    opts.env ?? testEnv(db),
    ctx,
    opts.now ?? T0,
  );
  await ctx.flush();
  return res;
}

async function row(e: string) {
  return db.prepare('SELECT * FROM waitlist WHERE email = ?1').bind(e.toLowerCase()).first<Record<string, unknown>>();
}

describe('留邮箱', () => {
  it('新邮箱：只发一封确认邮件（双重确认），记下语言、来源、IP 的 sha256；回答是 check_email', async () => {
    const s = stubFetch();
    vi.stubGlobal('fetch', s.fn);
    const e = email('new');
    const res = await sub(e, { attribution: { src: 'threads', cmp: 'tw1', ref: 'r1', start: 'bad start!' }, ip: '198.51.100.20' });
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ status: 'check_email' });
    const r = await row(e);
    expect(r).toMatchObject({ status: 'PENDING', lang: 'zh-TW', src: 'threads', cmp: 'tw1', ref: 'r1', start_param: null, confirm_sends: 1 });
    expect(r!.email).toBe(e.toLowerCase());
    // sha256("198.51.100.20")，不存 IP 原文
    expect(r!.ip_hash).toMatch(/^[0-9a-f]{64}$/);
    expect(JSON.stringify(r)).not.toContain('198.51.100.20');
    expect(s.mails).toHaveLength(1);
    const m = s.mails[0];
    expect(m.auth).toBe('Bearer ci_only_resend_key');
    expect(m.body.to).toEqual([e.toLowerCase()]);
    expect(m.body.subject).toBe(CONFIRM_MAIL['zh-TW'].subject);
    const [c, u] = links(m.body.text);
    expect(c).toBe(`https://www.example.test/data/confirm?t=${r!.confirm_token}`);
    expect(u).toBe(`https://www.example.test/data/unsubscribe?t=${r!.unsub_token}`);
    expect(m.body.headers?.['List-Unsubscribe']).toBe(`<${u}>`);
  });

  it('三种语言的邮件按订阅时的语言发；zh-HK 算繁中，不认识的算英文', async () => {
    const s = stubFetch();
    vi.stubGlobal('fetch', s.fn);
    for (const [lang, want] of [
      ['zh-CN', 'zh-CN'],
      ['en', 'en'],
      ['zh-HK', 'zh-TW'],
      ['fr', 'en'],
    ] as const) {
      const e = email(`lang-${lang}`);
      await sub(e, { lang });
      expect((await row(e))!.lang).toBe(want);
      expect(s.mails.at(-1)!.body.subject).toBe(CONFIRM_MAIL[want].subject);
    }
  });

  it('邮箱格式和页面同一套规则（C108）：空 → REQUIRED，不合法 → EMAIL_INVALID；前后空格、大写自动规范', async () => {
    vi.stubGlobal('fetch', stubFetch().fn);
    for (const [v, code] of [
      ['', 'REQUIRED'],
      ['   ', 'REQUIRED'],
      ['a@b', 'EMAIL_INVALID'],
      ['a b@example.com', 'EMAIL_INVALID'],
      [`${'x'.repeat(250)}@a.com`, 'EMAIL_INVALID'],
    ]) {
      const res = await sub(v);
      expect(res.status).toBe(400);
      expect(await res.json()).toMatchObject({ code: 'VALIDATION_FAILED', fields: [{ field: 'email', code }] });
    }
    const e = email('space');
    expect((await sub(`  ${e}  `)).status).toBe(200);
    expect((await row(e))!.email).toBe(e.toLowerCase());
  });

  it('和页面、后端同一组邮箱例子（backend/src/test/resources/email/vectors.json，C108）', async () => {
    vi.stubGlobal('fetch', stubFetch().fn);
    const file = path.join(CF_DIR, '../backend/src/test/resources/email/vectors.json');
    const cases = (JSON.parse(readFileSync(file, 'utf8')) as { cases: { input: string; ok: boolean }[] }).cases;
    for (const c of cases) expect((await sub(c.input)).status, c.input).toBe(c.ok ? 200 : 400);
  });

  it('陷阱字段有值：照样回答 check_email，什么都不做（不记、不发信）', async () => {
    const s = stubFetch();
    vi.stubGlobal('fetch', s.fn);
    const e = email('bot');
    const res = await sub(e, { website: 'http://spam' });
    expect(await res.json()).toEqual({ status: 'check_email' });
    expect(await row(e)).toBeNull();
    expect(s.mails).toHaveLength(0);
  });

  it('同一 IP 每小时 10 次（陷阱字段也算），第 11 次 429 带 Retry-After；下一个小时恢复', async () => {
    vi.stubGlobal('fetch', stubFetch().fn);
    const ip = freshIp();
    for (let i = 0; i < 9; i++) expect((await sub(email('ip'), { ip })).status).toBe(200);
    expect((await sub(email('ip'), { ip, website: 'x' })).status).toBe(200);
    const res = await sub(email('ip'), { ip, now: T0 + 1000 });
    expect(res.status).toBe(429);
    const body = (await res.json()) as { code: string; cooldownSeconds: number };
    expect(body.code).toBe('RATE_LIMITED');
    // T0 是整点，过了 1 秒，离下一个窗口还有 3599 秒
    expect(body.cooldownSeconds).toBe(3599);
    expect(res.headers.get('Retry-After')).toBe('3599');
    // 窗口结束前的最后一毫秒仍然被挡；正好到下一个窗口开始时恢复（C106：边界写清楚）
    expect((await sub(email('ip'), { ip, now: T0 + 3_599_999 })).status).toBe(429);
    expect((await sub(email('ip'), { ip, now: T0 + 3_600_000 })).status).toBe(200);
  });

  it('已经留过（待确认）：回答一样；10 分钟内不重发，之后重发同一个链接，最多 5 封', async () => {
    const s = stubFetch();
    vi.stubGlobal('fetch', s.fn);
    const e = email('resend');
    await sub(e);
    const first = links(s.mails[0].body.text)[0];
    // 不到 10 分钟：不发
    const r1 = await sub(e, { now: T0 + RULES.resendAfterMs - 1 });
    expect(await r1.json()).toEqual({ status: 'check_email' });
    expect(s.mails).toHaveLength(1);
    // 正好 10 分钟：重发，同一个确认链接，换成这次的语言
    await sub(e, { now: T0 + RULES.resendAfterMs, lang: 'en' });
    expect(s.mails).toHaveLength(2);
    expect(links(s.mails[1].body.text)[0]).toBe(first);
    expect(s.mails[1].body.subject).toBe(CONFIRM_MAIL.en.subject);
    let t = T0 + RULES.resendAfterMs;
    for (let i = 0; i < 5; i++) {
      t += RULES.resendAfterMs;
      await sub(e, { now: t });
    }
    expect(s.mails).toHaveLength(RULES.maxConfirmSends);
    expect((await row(e))!.confirm_sends).toBe(RULES.maxConfirmSends);
  });

  it('同时两次提交同一个已在名单的邮箱：只重发一封（带旧次数的条件更新）', async () => {
    const s = stubFetch();
    vi.stubGlobal('fetch', s.fn);
    const e = email('race');
    await sub(e);
    const t = T0 + RULES.resendAfterMs;
    await Promise.all([sub(e, { now: t }), sub(e, { now: t }), sub(e, { now: t })]);
    expect(s.mails).toHaveLength(2);
  });

  it('已确认的再留：回答一样，不发信', async () => {
    const s = stubFetch();
    vi.stubGlobal('fetch', s.fn);
    const e = email('confirmed');
    await sub(e);
    const t = tokenFrom(links(s.mails[0].body.text)[0]);
    expect((await confirm(post('/api/public/waitlist/confirm', { token: t }), testEnv(db), T0 + 1000)).status).toBe(200);
    const res = await sub(e, { now: T0 + 2 * RULES.resendAfterMs });
    expect(await res.json()).toEqual({ status: 'check_email' });
    expect(s.mails).toHaveLength(1);
  });

  it('等候页关着（WAITLIST_OPEN=false）：409 WAITLIST_CLOSED', async () => {
    const res = await sub(email('closed'), { env: testEnv(db, { WAITLIST_OPEN: 'false' }) });
    expect(res.status).toBe(409);
    expect(await res.json()).toMatchObject({ code: 'WAITLIST_CLOSED' });
  });

  it('没填 Resend key：照样记下订阅，不发信（未开通，不是故障）', async () => {
    const s = stubFetch();
    vi.stubGlobal('fetch', s.fn);
    const e = email('nokey');
    expect((await sub(e, { env: testEnv(db, { RESEND_API_KEY: '' }) })).status).toBe(200);
    expect((await row(e))!.status).toBe('PENDING');
    expect(s.calls).toHaveLength(0);
  });

  it('Resend 返回 5xx 时再试一次；403（key 没有发信域名的权限）不重试', async () => {
    const s500 = stubFetch({}, 503);
    vi.stubGlobal('fetch', s500.fn);
    await sub(email('r5xx'));
    expect(s500.mails).toHaveLength(2);
    const s403 = stubFetch({}, 403);
    vi.stubGlobal('fetch', s403.fn);
    await sub(email('r403'));
    expect(s403.mails).toHaveLength(1);
  });

  it('只接受本站页面的 JSON 请求：别的站点 403，不是 JSON 400', async () => {
    const ctx = testCtx();
    const cross = await subscribe(post('/api/public/waitlist', { email: email('x') }, freshIp(), { Origin: 'https://evil.example' }), testEnv(db), ctx, T0);
    expect(cross.status).toBe(403);
    const form = new Request('https://www.example.test/api/public/waitlist', { method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: 'email=a@b.co' });
    expect((await subscribe(form, testEnv(db), ctx, T0)).status).toBe(400);
  });
});

describe('确认和退订', () => {
  async function fresh(tag: string) {
    const s = stubFetch();
    vi.stubGlobal('fetch', s.fn);
    const e = email(tag);
    await sub(e);
    const [c, u] = links(s.mails[0].body.text);
    return { e, c: tokenFrom(c), u: tokenFrom(u), s };
  }
  const doConfirm = (token: unknown, now = T0 + 1000, env = testEnv(db)) => confirm(post('/api/public/waitlist/confirm', { token }), env, now);
  const doUnsub = (token: unknown, now = T0 + 1000) => unsubscribe(post('/api/public/waitlist/unsubscribe', { token }), testEnv(db), now);

  it('确认：待确认 → 已确认，返回状态和语言（不返回邮箱）；重复确认没有效果', async () => {
    const { e, c } = await fresh('confirm');
    const res = await doConfirm(c);
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body).toEqual({ status: 'confirmed', lang: 'zh-TW' });
    expect(JSON.stringify(body)).not.toContain('@');
    const at = (await row(e))!.confirmed_at;
    expect(await (await doConfirm(c, T0 + 5000)).json()).toEqual({ status: 'confirmed', lang: 'zh-TW' });
    expect((await row(e))!.confirmed_at).toBe(at);
  });

  it('链接无效 404（格式不对、不存在、用退订串去确认）；大写的随机串也认', async () => {
    const { c, u } = await fresh('invalid');
    for (const t of ['', 'abc', '0'.repeat(32), u, null, 123]) expect((await doConfirm(t)).status).toBe(404);
    expect((await doConfirm(c.toUpperCase())).status).toBe(200);
  });

  it('确认链接 7 天有效：正好 7 天还能确认，多 1 毫秒 410', async () => {
    const a = await fresh('exp-a');
    expect((await doConfirm(a.c, T0 + RULES.confirmValidMs)).status).toBe(200);
    const b = await fresh('exp-b');
    const res = await doConfirm(b.c, T0 + RULES.confirmValidMs + 1);
    expect(res.status).toBe(410);
    expect(await res.json()).toMatchObject({ code: 'WAITLIST_LINK_EXPIRED' });
  });

  it('等候页关了以后待确认的不能再确认（409）；已确认的照样返回', async () => {
    const a = await fresh('close-a');
    expect((await doConfirm(a.c, T0 + 1000, testEnv(db, { WAITLIST_OPEN: 'false' }))).status).toBe(409);
    const b = await fresh('close-b');
    await doConfirm(b.c);
    expect((await doConfirm(b.c, T0 + 2000, testEnv(db, { WAITLIST_OPEN: 'false' }))).status).toBe(200);
  });

  it('退订不用登录、任何状态都能退、重复也成功；退订后确认链接显示已退订', async () => {
    const { e, c, u } = await fresh('unsub');
    await doConfirm(c);
    expect(await (await doUnsub(u)).json()).toEqual({ status: 'unsubscribed', lang: 'zh-TW' });
    expect(await (await doUnsub(u, T0 + 9000)).json()).toEqual({ status: 'unsubscribed', lang: 'zh-TW' });
    expect((await row(e))!.status).toBe('UNSUBSCRIBED');
    expect(await (await doConfirm(c)).json()).toEqual({ status: 'unsubscribed', lang: 'zh-TW' });
    expect((await doUnsub('f'.repeat(32))).status).toBe(404);
  });

  it('退订后再留邮箱：10 分钟后重新走确认（新的确认链接，退订链接不变）', async () => {
    const { e, c, u, s } = await fresh('resub');
    await doUnsub(u);
    await sub(e, { now: T0 + RULES.resendAfterMs - 1 });
    expect(s.mails).toHaveLength(1);
    await sub(e, { now: T0 + RULES.resendAfterMs, attribution: { src: 'line', cmp: 'seed' } });
    expect(s.mails).toHaveLength(2);
    const [c2, u2] = links(s.mails[1].body.text).map(tokenFrom);
    expect(c2).not.toBe(c);
    expect(u2).toBe(u);
    expect(await row(e)).toMatchObject({ status: 'PENDING', src: 'line', cmp: 'seed', confirmed_at: null, unsubscribed_at: null, confirm_sends: 1 });
    expect((await doConfirm(c)).status).toBe(404);
    expect((await doConfirm(c2, T0 + RULES.resendAfterMs + 1)).status).toBe(200);
  });
});
