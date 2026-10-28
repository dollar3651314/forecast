/**
 * 接口的返回格式（forecast S19）：和后端一样（common/web/ApiError）——出错时 `{code, message, fields:[{field, code}]}`，限次时多一个 `cooldownSeconds`，
 * 客户端的 api/errors.ts 原样能认。每个接口的返回都带安全头（和测试环境 deploy/nginx/security.inc 一样）。
 */
export const SECURITY_HEADERS: Record<string, string> = {
  'X-Content-Type-Options': 'nosniff',
  'X-Frame-Options': 'DENY',
  'Referrer-Policy': 'strict-origin-when-cross-origin',
  'Strict-Transport-Security': 'max-age=86400',
};

export function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', ...SECURITY_HEADERS, ...headers },
  });
}

export interface FieldError {
  field: string;
  code: string;
}

export function error(status: number, code: string, message: string, fields: FieldError[] = [], extra: Record<string, unknown> = {}, headers: Record<string, string> = {}): Response {
  return json({ code, message, fields, ...extra }, status, headers);
}

export const notFound = () => error(404, 'NOT_FOUND', '找不到');

/** 写接口只接受 JSON（浏览器跨站提交的表单发不出这种请求），并且只接受本站页面发起（Origin 不是本站时 403，和测试环境约定 14 一样） */
export async function readJson(req: Request): Promise<{ ok: true; body: Record<string, unknown> } | { ok: false; res: Response }> {
  const origin = req.headers.get('Origin');
  if (origin && origin !== new URL(req.url).origin) return { ok: false, res: error(403, 'FORBIDDEN', '只接受本站页面的请求') };
  const ct = (req.headers.get('Content-Type') || '').toLowerCase();
  if (!ct.startsWith('application/json')) return { ok: false, res: error(400, 'BAD_REQUEST', '请求格式不对') };
  try {
    const text = await req.text();
    if (text.length > 16 * 1024) return { ok: false, res: error(400, 'BAD_REQUEST', '请求太大') };
    const body: unknown = text ? JSON.parse(text) : {};
    if (!body || typeof body !== 'object' || Array.isArray(body)) return { ok: false, res: error(400, 'BAD_REQUEST', '请求格式不对') };
    return { ok: true, body: body as Record<string, unknown> };
  } catch {
    return { ok: false, res: error(400, 'BAD_REQUEST', '请求格式不对') };
  }
}

/** 访客的 IP：Cloudflare 填的 CF-Connecting-IP（客户端改不了）；本机测试没有时用空串 */
export function clientIp(req: Request): string {
  return req.headers.get('CF-Connecting-IP') || '';
}

export function str(v: unknown): string | undefined {
  return typeof v === 'string' ? v : undefined;
}
