/**
 * 名单只给 Amos 看（forecast S19，P12、P14、P15）：Access 签名校验（用测试生成的 RSA 密钥对，替身给公钥）、页面和 CSV 的邮箱打码、
 * 按来源统计、迁移用的导出（完整邮箱，只含已确认和已退订，分页）；没配 Access 时 404。
 */
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { resetCertCache, teamDomain, verifyAccess } from '../src/access';
import { admin, cell, EXPORT_FORMAT } from '../src/admin';
import { visit } from '../src/visit';
import { confirm, subscribe, unsubscribe } from '../src/waitlist';
import { freshIp, links, newDb, post, stubFetch, testCtx, testEnv, tokenFrom } from './helpers';

const TEAM = 'https://quickcome.cloudflareaccess.com';
const AUD = 'ci_only_aud_tag';
const NOW = Date.parse('2026-10-28T09:00:00.000Z');

let dispose: () => Promise<void>;
let db: D1Database;
let keys: CryptoKeyPair;
let jwk: JsonWebKey & { kid?: string };

function b64url(data: Uint8Array | string): string {
  const bytes = typeof data === 'string' ? new TextEncoder().encode(data) : data;
  return Buffer.from(bytes).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

async function jwt(payload: Record<string, unknown>, kid = 'k1', key = keys.privateKey): Promise<string> {
  const h = b64url(JSON.stringify({ alg: 'RS256', kid, typ: 'JWT' }));
  const p = b64url(JSON.stringify(payload));
  const sig = new Uint8Array(await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, new TextEncoder().encode(`${h}.${p}`)));
  return `${h}.${p}.${b64url(sig)}`;
}

const good = () => ({ aud: [AUD], iss: TEAM, email: 'amos@example.com', exp: Math.floor(NOW / 1000) + 600, iat: Math.floor(NOW / 1000) });
const env = () => testEnv(db, { ACCESS_TEAM_DOMAIN: 'quickcome.cloudflareaccess.com', ACCESS_AUD: AUD });
const get = (path: string, token?: string) =>
  new Request(`https://www.example.test${path}`, { headers: token ? { 'Cf-Access-Jwt-Assertion': token } : {} });

function stubCerts() {
  const s = stubFetch({ [`${TEAM}/cdn-cgi/access/certs`]: () => new Response(JSON.stringify({ keys: [jwk] }), { status: 200 }) });
  vi.stubGlobal('fetch', s.fn);
  return s;
}

beforeAll(async () => {
  ({ dispose, db } = await newDb());
  keys = (await crypto.subtle.generateKey({ name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256' }, true, [
    'sign',
    'verify',
  ])) as CryptoKeyPair;
  jwk = { ...((await crypto.subtle.exportKey('jwk', keys.publicKey)) as JsonWebKey), kid: 'k1' };
  // 准备数据：已确认 2 个（threads / tw1、line / seed）、待确认 1 个、已退订 1 个；带来源的访问 3 次
  const s = stubFetch();
  vi.stubGlobal('fetch', s.fn);
  const subs: [string, string, string | undefined][] = [
    ['a1@example.com', 'threads', 'tw1'],
    ['b2@example.com', 'line', 'seed'],
    ['c3@example.com', 'threads', 'tw1'],
    ['d4@example.com', '', undefined],
  ];
  for (const [e, src, cmp] of subs) {
    const ctx = testCtx();
    await subscribe(post('/api/public/waitlist', { email: e, lang: 'zh-TW', attribution: src ? { src, cmp } : undefined }, freshIp()), testEnv(db), ctx, NOW - 3_600_000);
    await ctx.flush();
  }
  const tok = (i: number) => links(s.mails[i].body.text).map(tokenFrom);
  await confirm(post('/api/public/waitlist/confirm', { token: tok(0)[0] }), testEnv(db), NOW - 3_000_000);
  await confirm(post('/api/public/waitlist/confirm', { token: tok(1)[0] }), testEnv(db), NOW - 3_000_000);
  await confirm(post('/api/public/waitlist/confirm', { token: tok(3)[0] }), testEnv(db), NOW - 3_000_000);
  await unsubscribe(post('/api/public/waitlist/unsubscribe', { token: tok(3)[1] }), testEnv(db), NOW - 2_000_000);
  for (let i = 0; i < 3; i++) await visit(post('/api/attribution/visit', { src: 'threads', cmp: 'tw1' }, freshIp()), testEnv(db), NOW);
  vi.unstubAllGlobals();
});
afterAll(async () => {
  await dispose();
});
afterEach(() => {
  vi.unstubAllGlobals();
  resetCertCache();
});

describe('Access 签名校验', () => {
  it('团队域名带不带 https:// 都行；不是 cloudflareaccess.com 的不认', () => {
    expect(teamDomain(testEnv(db, { ACCESS_TEAM_DOMAIN: 'quickcome.cloudflareaccess.com/' }))).toBe(TEAM);
    expect(teamDomain(testEnv(db, { ACCESS_TEAM_DOMAIN: TEAM }))).toBe(TEAM);
    expect(teamDomain(testEnv(db, { ACCESS_TEAM_DOMAIN: 'https://evil.example.com' }))).toBeNull();
  });

  it('签名、aud、iss、过期都对才放行', async () => {
    stubCerts();
    expect(await verifyAccess(get('/api/admin/', await jwt(good())), env(), NOW)).toEqual({ email: 'amos@example.com' });
    expect(await verifyAccess(get('/api/admin/', await jwt({ ...good(), aud: ['other'] })), env(), NOW)).toBeNull();
    expect(await verifyAccess(get('/api/admin/', await jwt({ ...good(), iss: 'https://other.cloudflareaccess.com' })), env(), NOW)).toBeNull();
    // exp 等于现在的那一秒已经过期（C106）
    expect(await verifyAccess(get('/api/admin/', await jwt({ ...good(), exp: Math.floor(NOW / 1000) })), env(), NOW)).toBeNull();
    expect(await verifyAccess(get('/api/admin/'), env(), NOW)).toBeNull();
    expect(await verifyAccess(get('/api/admin/', 'a.b.c'), env(), NOW)).toBeNull();
  });

  it('别的密钥签的不放行；找不到 kid 时重新取一次公钥（轮换）', async () => {
    const other = (await crypto.subtle.generateKey({ name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256' }, true, [
      'sign',
      'verify',
    ])) as CryptoKeyPair;
    const s = stubCerts();
    expect(await verifyAccess(get('/api/admin/', await jwt(good(), 'k1', other.privateKey)), env(), NOW)).toBeNull();
    expect(await verifyAccess(get('/api/admin/', await jwt(good(), 'k2')), env(), NOW)).toBeNull();
    expect(s.calls.filter((u) => u.endsWith('/certs')).length).toBe(2);
  });
});

describe('/api/admin/', () => {
  it('没配 Access：404（默认关）', async () => {
    expect((await admin(get('/api/admin/'), testEnv(db), '/api/admin/', NOW)).status).toBe(404);
  });

  it('配了但没有（或错误的）Access 令牌：403', async () => {
    stubCerts();
    expect((await admin(get('/api/admin/'), env(), '/api/admin/', NOW)).status).toBe(403);
    expect((await admin(get('/api/admin/', await jwt({ ...good(), aud: 'x' })), env(), '/api/admin/', NOW)).status).toBe(403);
  });

  it('页面：各状态数量、按来源（含访问数）、最近的订阅邮箱打码', async () => {
    stubCerts();
    const res = await admin(get('/api/admin/', await jwt(good())), env(), '/api/admin/', NOW);
    expect(res.status).toBe(200);
    expect(res.headers.get('Cache-Control')).toBe('no-store');
    const html = await res.text();
    expect(html).toContain('a***@example.com');
    expect(html).not.toContain('a1@example.com');
    expect(html).toMatch(/已确认<b>2<\/b>/);
    expect(html).toMatch(/待确认<b>1<\/b>/);
    expect(html).toMatch(/已退订<b>1<\/b>/);
    expect(html).toMatch(/<td>threads<\/td><td>tw1<\/td><td>3<\/td><td>1<\/td><td>1<\/td><td>0<\/td>/);
  });

  it('waitlist.csv：邮箱打码；sources.csv：按来源', async () => {
    stubCerts();
    const bytes = new Uint8Array(await (await admin(get('/api/admin/waitlist.csv', await jwt(good())), env(), '/api/admin/waitlist.csv', NOW)).arrayBuffer());
    // 开头有 UTF-8 BOM（Excel 打开中文不乱码）
    expect([...bytes.slice(0, 3)]).toEqual([0xef, 0xbb, 0xbf]);
    const w = new TextDecoder().decode(bytes);
    expect(w.split('\r\n')[0]).toBe('"id","email_masked","lang","status","src","cmp","ref","start","created_at","confirmed_at","unsubscribed_at"');
    expect(w).toContain('"a***@example.com"');
    expect(w).not.toMatch(/a1@example\.com/);
    const s = await (await admin(get('/api/admin/sources.csv', await jwt(good())), env(), '/api/admin/sources.csv', NOW)).text();
    expect(s).toContain('"threads","tw1","3","1","1","0"');
    expect(s).toContain('"line","seed","0","0","1","0"');
  });

  it('export.json：完整邮箱，只含已确认和已退订，带语言、来源、时间、退订串', async () => {
    stubCerts();
    const res = await admin(get('/api/admin/export.json', await jwt(good())), env(), '/api/admin/export.json', NOW);
    expect(res.headers.get('Content-Disposition')).toMatch(/^attachment; filename="waitlist-export-2026-10-28-0\.json"$/);
    const body = (await res.json()) as { format: string; next: string | null; items: Record<string, unknown>[] };
    expect(body.format).toBe(EXPORT_FORMAT);
    expect(body.next).toBeNull();
    expect(body.items.map((i) => [i.email, i.status])).toEqual([
      ['a1@example.com', 'confirmed'],
      ['b2@example.com', 'confirmed'],
      ['d4@example.com', 'unsubscribed'],
    ]);
    expect(body.items[0]).toMatchObject({ lang: 'zh-TW', src: 'threads', cmp: 'tw1', ref: null, start: null });
    expect(body.items[0].confirmedAt).toBe(new Date(NOW - 3_000_000).toISOString());
    expect(body.items[0].unsubToken).toMatch(/^[0-9a-f]{32}$/);
    expect(body.items[0].ipHash).toMatch(/^[0-9a-f]{64}$/);
    expect(body.items[2].unsubscribedAt).toBe(new Date(NOW - 2_000_000).toISOString());
  });

  it('只能 GET；不认识的路径 404', async () => {
    stubCerts();
    const t = await jwt(good());
    expect((await admin(new Request('https://www.example.test/api/admin/', { method: 'POST', headers: { 'Cf-Access-Jwt-Assertion': t } }), env(), '/api/admin/', NOW)).status).toBe(405);
    expect((await admin(get('/api/admin/nope', t), env(), '/api/admin/nope', NOW)).status).toBe(404);
  });

  it('CSV 一格：以 = + - @ 开头的加 \'，防止被表格软件当成公式', () => {
    expect(cell('=1+1')).toBe(`"'=1+1"`);
    expect(cell('-x')).toBe(`"'-x"`);
    expect(cell('a"b')).toBe('"a""b"');
    expect(cell(null)).toBe('""');
  });
});
