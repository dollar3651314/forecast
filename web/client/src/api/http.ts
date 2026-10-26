/**
 * 真实接口的 HTTP 调用（S17，只在 real 模式使用）。
 * - 同源 `/api/...`，`credentials: 'same-origin'`：登录态在 HttpOnly Cookie 里，前端拿不到也不保存令牌；
 * - 出错统一换成 ApiError（errors.ts 的 fromServer）；断网 → network，超时 → timeout；
 * - 返回 401 时通知登录状态（AuthProvider 清空会话，路由守卫回到登录页）；
 * - 响应里的 null 一律当成"没有这个字段"（undefined），和 types.ts 的可选字段一致。
 */
import { deviceId } from './device';
import { ApiError, fromServer, type ServerErrorBody, type ServerErrorOptions } from './errors';

const TIMEOUT_MS = 15_000;

type UnauthorizedHandler = () => void;
const unauthorizedHandlers = new Set<UnauthorizedHandler>();

/** 登录失效（任意接口返回 401）时回调；返回取消函数 */
export function onUnauthorized(h: UnauthorizedHandler): () => void {
  unauthorizedHandlers.add(h);
  return () => unauthorizedHandlers.delete(h);
}

export interface RequestOptions extends ServerErrorOptions {
  /** JSON 请求体 */
  body?: unknown;
  /** multipart 请求体（KYC 上传） */
  form?: FormData;
  /** 查询参数；undefined 的不带 */
  query?: Record<string, string | number | undefined>;
  /** 401 时不触发"回到登录页"（例如启动时检查是否已登录） */
  quiet401?: boolean;
  /** 带上设备号（请求头 X-Device-Id，forecast S10：登录、注册时，关联账户的线索，见 ./device.ts） */
  device?: boolean;
}

/** 把 JSON 里的 null 去掉 */
function parseJson(text: string): unknown {
  return text ? JSON.parse(text, (_k, v: unknown) => (v === null ? undefined : v)) : undefined;
}

export function buildUrl(path: string, query?: RequestOptions['query']): string {
  if (!query) return path;
  const qs = Object.entries(query)
    .filter(([, v]) => v !== undefined && v !== '')
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`)
    .join('&');
  return qs ? `${path}?${qs}` : path;
}

export async function request<T>(method: 'GET' | 'POST' | 'PUT' | 'DELETE', path: string, opt: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (opt.device) headers['X-Device-Id'] = deviceId();
  let body: BodyInit | undefined;
  if (opt.form) body = opt.form;
  else if (opt.body !== undefined) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(opt.body);
  }
  const ctrl = typeof AbortController !== 'undefined' ? new AbortController() : undefined;
  const timer = ctrl ? setTimeout(() => ctrl.abort(), TIMEOUT_MS) : undefined;
  let res: Response;
  try {
    res = await fetch(buildUrl(path, opt.query), { method, headers, body, credentials: 'same-origin', signal: ctrl?.signal });
  } catch (e) {
    throw new ApiError((e as { name?: string })?.name === 'AbortError' ? 'timeout' : 'network');
  } finally {
    if (timer) clearTimeout(timer);
  }
  let text = '';
  try {
    text = await res.text();
  } catch {
    throw new ApiError('network');
  }
  let data: unknown;
  try {
    data = parseJson(text);
  } catch {
    // 网关（Nginx）返回的 HTML 错误页等
    data = undefined;
  }
  if (!res.ok) {
    if (res.status === 401 && !opt.quiet401) unauthorizedHandlers.forEach((h) => h());
    throw fromServer(res.status, data as ServerErrorBody | undefined, opt);
  }
  return data as T;
}

export const get = <T>(path: string, opt?: RequestOptions) => request<T>('GET', path, opt);
export const post = <T>(path: string, body?: unknown, opt?: RequestOptions) => request<T>('POST', path, { ...opt, body });
export const put = <T>(path: string, body?: unknown, opt?: RequestOptions) => request<T>('PUT', path, { ...opt, body });
export const del = <T>(path: string, opt?: RequestOptions) => request<T>('DELETE', path, opt);

/** 后端少数时间字段是不带时区的 UTC 时间（LocalDateTime），补上 Z，避免浏览器按本地时间解析 */
export function utc(t: string | undefined): string | undefined {
  if (!t) return t;
  return /([zZ]|[+-]\d{2}:?\d{2})$/.test(t) || !t.includes('T') ? t : `${t}Z`;
}
