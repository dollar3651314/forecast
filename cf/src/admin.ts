/**
 * 名单只给 Amos 看（forecast S19，P12、P14、P15）。都在 /api/admin/ 下，先过 Cloudflare Access，再由 access.ts 验签名；没配置 Access 时 404。
 * - GET /api/admin/                页面：各状态数量、按来源的订阅数和访问数、最近 100 个订阅（邮箱打码）、下载链接
 * - GET /api/admin/waitlist.csv    全部订阅（邮箱打码）
 * - GET /api/admin/sources.csv     按 src / cmp 的订阅数（待确认、已确认、已退订）和带来源的访问数
 * - GET /api/admin/export.json     **完整邮箱**，只用于开放交易时导入后端（P15）：只含已确认、已退订；每次最多 2000 个，返回 next 接着取
 * 每次请求的 CPU 有上限（Free 10 ms），所以列表和导出都分页。
 */
import { accessConfigured, verifyAccess } from './access';
import type { Env } from './env';
import { error, notFound, SECURITY_HEADERS } from './http';
import { mask } from './util';

export const EXPORT_FORMAT = 'fc-cf-waitlist-v1';
export const EXPORT_PAGE = 2000;
export const CSV_PAGE = 5000;

interface FullRow {
  id: number;
  email: string;
  lang: string;
  status: string;
  src: string | null;
  cmp: string | null;
  ref: string | null;
  start_param: string | null;
  ip_hash: string;
  unsub_token: string;
  created_at: string;
  confirmed_at: string | null;
  unsubscribed_at: string | null;
}

export async function admin(req: Request, env: Env, path: string, now = Date.now()): Promise<Response> {
  if (!accessConfigured(env)) return notFound();
  if (req.method !== 'GET') return error(405, 'METHOD_NOT_ALLOWED', '只能查看');
  const user = await verifyAccess(req, env, now);
  if (!user) return error(403, 'FORBIDDEN', '需要先通过 Cloudflare Access 登录');
  const url = new URL(req.url);
  console.log(JSON.stringify({ event: 'admin.view', path, user: mask(user.email) }));
  if (path === '/api/admin' || path === '/api/admin/') return page(env);
  if (path === '/api/admin/waitlist.csv') return waitlistCsv(env, url);
  if (path === '/api/admin/sources.csv') return sourcesCsv(env);
  if (path === '/api/admin/export.json') return exportJson(env, url, now);
  return notFound();
}

const NO_STORE = { 'Cache-Control': 'no-store', ...SECURITY_HEADERS };

function esc(v: unknown): string {
  return String(v ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!);
}

/** CSV 一格：加引号；以 = + - @ 开头的前面加 '，防止在表格软件里被当成公式 */
export function cell(v: unknown): string {
  let s = v === null || v === undefined ? '' : String(v);
  if (/^[=+\-@]/.test(s)) s = `'${s}`;
  return `"${s.replace(/"/g, '""')}"`;
}

function csv(rows: unknown[][]): string {
  return '\ufeff' + rows.map((r) => r.map(cell).join(',')).join('\r\n') + '\r\n';
}

function after(url: URL): number {
  const n = Number(url.searchParams.get('after') ?? '0');
  return Number.isSafeInteger(n) && n > 0 ? n : 0;
}

interface SourceCount {
  src: string;
  cmp: string;
  pending: number;
  confirmed: number;
  unsubscribed: number;
  visits: number;
}

async function sourceCounts(env: Env): Promise<SourceCount[]> {
  const subs = await env.DB.prepare(
    "SELECT COALESCE(src, '') AS src, COALESCE(cmp, '') AS cmp, " +
      "SUM(status = 'PENDING') AS pending, SUM(status = 'CONFIRMED') AS confirmed, SUM(status = 'UNSUBSCRIBED') AS unsubscribed " +
      'FROM waitlist GROUP BY 1, 2',
  ).all<{ src: string; cmp: string; pending: number; confirmed: number; unsubscribed: number }>();
  const visits = await env.DB.prepare('SELECT src, cmp, SUM(count) AS visits FROM visit_daily GROUP BY 1, 2').all<{ src: string; cmp: string; visits: number }>();
  const map = new Map<string, SourceCount>();
  const get = (src: string, cmp: string) => {
    const k = `${src}\u0000${cmp}`;
    let v = map.get(k);
    if (!v) map.set(k, (v = { src, cmp, pending: 0, confirmed: 0, unsubscribed: 0, visits: 0 }));
    return v;
  };
  for (const r of subs.results ?? []) Object.assign(get(r.src, r.cmp), { pending: r.pending, confirmed: r.confirmed, unsubscribed: r.unsubscribed });
  for (const r of visits.results ?? []) get(r.src, r.cmp).visits = r.visits;
  return [...map.values()].sort((a, b) => b.confirmed - a.confirmed || b.pending - a.pending || b.visits - a.visits || a.src.localeCompare(b.src));
}

async function counts(env: Env): Promise<Record<string, number>> {
  const r = await env.DB.prepare('SELECT status, COUNT(*) AS n FROM waitlist GROUP BY status').all<{ status: string; n: number }>();
  const out: Record<string, number> = { PENDING: 0, CONFIRMED: 0, UNSUBSCRIBED: 0 };
  for (const x of r.results ?? []) out[x.status] = x.n;
  return out;
}

const STATUS_ZH: Record<string, string> = { PENDING: '待确认', CONFIRMED: '已确认', UNSUBSCRIBED: '已退订' };

async function page(env: Env): Promise<Response> {
  const c = await counts(env);
  const sources = await sourceCounts(env);
  const recent = await env.DB.prepare(
    'SELECT id, email, lang, status, src, cmp, ref, created_at, confirmed_at, unsubscribed_at FROM waitlist ORDER BY id DESC LIMIT 100',
  ).all<FullRow>();
  const total = c.PENDING + c.CONFIRMED + c.UNSUBSCRIBED;
  const pages = Math.max(1, Math.ceil((c.CONFIRMED + c.UNSUBSCRIBED) / EXPORT_PAGE));
  const html = `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex"><title>等候页订阅（Cloudflare）</title>
<style>
:root{--fg:#0b1f3a;--muted:#5b6b82;--line:#e3e8ef;--bg:#fff;--card:#f6f8fb}
@media (prefers-color-scheme: dark){:root{--fg:#e6edf3;--muted:#9aa7b8;--line:#2a3441;--bg:#0d1117;--card:#161b22}}
body{margin:0;padding:16px;font:14px/1.6 system-ui,-apple-system,"PingFang TC","Microsoft JhengHei",sans-serif;color:var(--fg);background:var(--bg)}
h1{font-size:20px;margin:0 0 4px}h2{font-size:16px;margin:24px 0 8px}.muted{color:var(--muted)}
.cards{display:flex;flex-wrap:wrap;gap:8px}.card{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:8px 12px;min-width:96px}
.card b{display:block;font-size:20px}.wrap{overflow-x:auto}table{border-collapse:collapse;min-width:100%}
th,td{border-bottom:1px solid var(--line);padding:6px 8px;text-align:left;white-space:nowrap}th{color:var(--muted);font-weight:600}
a{color:#1d4ed8}@media (prefers-color-scheme: dark){a{color:#7aa2ff}}
</style></head><body>
<h1>等候页订阅（Cloudflare）</h1>
<p class="muted">只给 Amos 看；邮箱已打码。时间是 UTC。</p>
<div class="cards">
<div class="card">合计<b>${total}</b></div>
<div class="card">已确认<b>${c.CONFIRMED}</b></div>
<div class="card">待确认<b>${c.PENDING}</b></div>
<div class="card">已退订<b>${c.UNSUBSCRIBED}</b></div>
</div>
<h2>按来源（src / cmp）</h2>
<p><a href="/api/admin/sources.csv">下载 sources.csv</a></p>
<div class="wrap"><table><thead><tr><th>src</th><th>cmp</th><th>访问</th><th>待确认</th><th>已确认</th><th>已退订</th></tr></thead><tbody>
${sources.map((s) => `<tr><td>${esc(s.src || '（无）')}</td><td>${esc(s.cmp || '（无）')}</td><td>${s.visits}</td><td>${s.pending}</td><td>${s.confirmed}</td><td>${s.unsubscribed}</td></tr>`).join('\n') || '<tr><td colspan="6" class="muted">还没有数据</td></tr>'}
</tbody></table></div>
<h2>最近 100 个订阅</h2>
<p><a href="/api/admin/waitlist.csv">下载全部（邮箱打码）waitlist.csv</a></p>
<div class="wrap"><table><thead><tr><th>#</th><th>邮箱</th><th>语言</th><th>状态</th><th>src</th><th>cmp</th><th>ref</th><th>留邮箱</th><th>确认</th><th>退订</th></tr></thead><tbody>
${(recent.results ?? []).map((r) => `<tr><td>${r.id}</td><td>${esc(mask(r.email))}</td><td>${esc(r.lang)}</td><td>${esc(STATUS_ZH[r.status] ?? r.status)}</td><td>${esc(r.src)}</td><td>${esc(r.cmp)}</td><td>${esc(r.ref)}</td><td>${esc(r.created_at)}</td><td>${esc(r.confirmed_at)}</td><td>${esc(r.unsubscribed_at)}</td></tr>`).join('\n') || '<tr><td colspan="10" class="muted">还没有订阅</td></tr>'}
</tbody></table></div>
<h2>开放交易时导入后端（P15）</h2>
<p>下载 <a href="/api/admin/export.json">export.json</a>（<b>完整邮箱</b>，只含已确认和已退订；超过 ${EXPORT_PAGE} 个时文件里有 <code>next</code>，按它再下载下一份，约 ${pages} 份），
在后台"等候页订阅 → 从 Cloudflare 导入"上传。导入完删掉下载的文件。</p>
</body></html>`;
  return new Response(html, { headers: { 'Content-Type': 'text/html; charset=utf-8', ...NO_STORE } });
}

async function waitlistCsv(env: Env, url: URL): Promise<Response> {
  const from = after(url);
  const r = await env.DB.prepare(
    'SELECT id, email, lang, status, src, cmp, ref, start_param, created_at, confirmed_at, unsubscribed_at FROM waitlist WHERE id > ?1 ORDER BY id LIMIT ?2',
  )
    .bind(from, CSV_PAGE)
    .all<FullRow>();
  const rows = r.results ?? [];
  const body = csv([
    ['id', 'email_masked', 'lang', 'status', 'src', 'cmp', 'ref', 'start', 'created_at', 'confirmed_at', 'unsubscribed_at'],
    ...rows.map((x) => [x.id, mask(x.email), x.lang, x.status.toLowerCase(), x.src, x.cmp, x.ref, x.start_param, x.created_at, x.confirmed_at, x.unsubscribed_at]),
  ]);
  const headers: Record<string, string> = { 'Content-Type': 'text/csv; charset=utf-8', 'Content-Disposition': 'attachment; filename="waitlist.csv"', ...NO_STORE };
  if (rows.length === CSV_PAGE) headers.Link = `</api/admin/waitlist.csv?after=${rows[rows.length - 1].id}>; rel="next"`;
  return new Response(body, { headers });
}

async function sourcesCsv(env: Env): Promise<Response> {
  const s = await sourceCounts(env);
  const body = csv([['src', 'cmp', 'visits', 'pending', 'confirmed', 'unsubscribed'], ...s.map((x) => [x.src, x.cmp, x.visits, x.pending, x.confirmed, x.unsubscribed])]);
  return new Response(body, { headers: { 'Content-Type': 'text/csv; charset=utf-8', 'Content-Disposition': 'attachment; filename="sources.csv"', ...NO_STORE } });
}

async function exportJson(env: Env, url: URL, now: number): Promise<Response> {
  const from = after(url);
  const r = await env.DB.prepare(
    'SELECT id, email, lang, status, src, cmp, ref, start_param, ip_hash, unsub_token, created_at, confirmed_at, unsubscribed_at FROM waitlist ' +
      "WHERE id > ?1 AND status IN ('CONFIRMED', 'UNSUBSCRIBED') ORDER BY id LIMIT ?2",
  )
    .bind(from, EXPORT_PAGE)
    .all<FullRow>();
  const rows = r.results ?? [];
  const body = {
    format: EXPORT_FORMAT,
    exportedAt: new Date(now).toISOString(),
    environment: env.ENVIRONMENT ?? 'production',
    after: from,
    next: rows.length === EXPORT_PAGE ? `/api/admin/export.json?after=${rows[rows.length - 1].id}` : null,
    items: rows.map((x) => ({
      email: x.email,
      lang: x.lang,
      status: x.status.toLowerCase(),
      src: x.src,
      cmp: x.cmp,
      ref: x.ref,
      start: x.start_param,
      createdAt: x.created_at,
      confirmedAt: x.confirmed_at,
      unsubscribedAt: x.unsubscribed_at,
      unsubToken: x.unsub_token,
      ipHash: x.ip_hash,
    })),
  };
  const stamp = new Date(now).toISOString().slice(0, 10);
  return new Response(JSON.stringify(body, null, 1), {
    headers: { 'Content-Type': 'application/json; charset=utf-8', 'Content-Disposition': `attachment; filename="waitlist-export-${stamp}-${from}.json"`, ...NO_STORE },
  });
}
