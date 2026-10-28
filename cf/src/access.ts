/**
 * Cloudflare Access 的签名校验（forecast S19，名单只给 Amos 看）。
 * Access 在放行的请求头里加 Cf-Access-Jwt-Assertion（RS256 签名的 JWT）；Worker 自己再验一遍：签名（团队域名下 /cdn-cgi/access/certs 的公钥，按 kid 找）、
 * aud 包含 ACCESS_AUD、iss 等于团队域名、没过期。为什么 Access 之外还要验：带静态资源的 Worker 拿不到 ctx.access（Cloudflare 文档），
 * 而且万一 Access 应用的路径配错了，Worker 仍然挡住。ACCESS_TEAM_DOMAIN、ACCESS_AUD 没填时一律不放行（/api/admin/ 返回 404）。
 * 不引入第三方库：WebCrypto 的 RSASSA-PKCS1-v1_5 + SHA-256。
 */
import type { Env } from './env';

export interface AccessUser {
  email: string | null;
}

export function accessConfigured(env: Env): boolean {
  return !!(teamDomain(env) && env.ACCESS_AUD?.trim());
}

/** 规范成 https://<团队>.cloudflareaccess.com（Amos 填的时候带不带 https:// 都行） */
export function teamDomain(env: Env): string | null {
  const v = env.ACCESS_TEAM_DOMAIN?.trim().replace(/\/+$/, '');
  if (!v) return null;
  const withScheme = /^https:\/\//.test(v) ? v : `https://${v}`;
  return /^https:\/\/[A-Za-z0-9-]+\.cloudflareaccess\.com$/.test(withScheme) ? withScheme : null;
}

function b64url(s: string): Uint8Array {
  const pad = s.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((s.length + 3) % 4);
  const bin = atob(pad);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

function decodeJson(part: string): Record<string, unknown> {
  return JSON.parse(new TextDecoder().decode(b64url(part))) as Record<string, unknown>;
}

interface Jwk extends JsonWebKey {
  kid?: string;
}

let certCache: { team: string; at: number; keys: Jwk[] } | null = null;
const CERT_TTL_MS = 10 * 60 * 1000;

export function resetCertCache(): void {
  certCache = null;
}

async function keys(team: string, now: number, refresh: boolean): Promise<Jwk[]> {
  if (!refresh && certCache && certCache.team === team && now - certCache.at < CERT_TTL_MS) return certCache.keys;
  const res = await fetch(`${team}/cdn-cgi/access/certs`, { signal: AbortSignal.timeout(5000) });
  if (!res.ok) throw new Error(`取 Access 公钥失败 HTTP ${res.status}`);
  const body = (await res.json()) as { keys?: Jwk[] };
  const ks = Array.isArray(body.keys) ? body.keys : [];
  certCache = { team, at: now, keys: ks };
  return ks;
}

/** 验证通过返回用户（邮箱可能为空，例如服务令牌）；不通过返回 null */
export async function verifyAccess(req: Request, env: Env, now = Date.now()): Promise<AccessUser | null> {
  const team = teamDomain(env);
  const aud = env.ACCESS_AUD?.trim();
  if (!team || !aud) return null;
  const jwt = req.headers.get('Cf-Access-Jwt-Assertion');
  if (!jwt) return null;
  const parts = jwt.split('.');
  if (parts.length !== 3) return null;
  try {
    const header = decodeJson(parts[0]);
    const payload = decodeJson(parts[1]);
    if (header.alg !== 'RS256') return null;
    const kid = typeof header.kid === 'string' ? header.kid : undefined;
    let ks = await keys(team, now, false);
    let jwk = ks.find((k) => k.kid === kid);
    if (!jwk) {
      // 公钥轮换了：重新取一次
      ks = await keys(team, now, true);
      jwk = ks.find((k) => k.kid === kid);
    }
    if (!jwk) return null;
    const key = await crypto.subtle.importKey('jwk', jwk, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['verify']);
    const ok = await crypto.subtle.verify('RSASSA-PKCS1-v1_5', key, b64url(parts[2]), new TextEncoder().encode(`${parts[0]}.${parts[1]}`));
    if (!ok) return null;
    const auds = Array.isArray(payload.aud) ? payload.aud : [payload.aud];
    if (!auds.includes(aud)) return null;
    if (payload.iss !== team) return null;
    const sec = Math.floor(now / 1000);
    if (typeof payload.exp !== 'number' || payload.exp <= sec) return null;
    if (typeof payload.nbf === 'number' && payload.nbf > sec + 60) return null;
    return { email: typeof payload.email === 'string' ? payload.email : null };
  } catch (e) {
    console.log(JSON.stringify({ event: 'access.verify_error', error: String((e as Error)?.message || e).slice(0, 200) }));
    return null;
  }
}
