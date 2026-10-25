/** 测试工具：fetch 替身。按 "方法 路径"（不含查询参数）返回响应，记录每次调用 */
export interface FakeResponse {
  status?: number;
  body?: unknown;
}

export type FakeRoutes = Record<string, FakeResponse | ((url: string, init: RequestInit) => FakeResponse)>;

export function fakeFetch(routes: FakeRoutes) {
  const calls: { method: string; url: string; init: RequestInit; body: unknown }[] = [];
  const fn = vi.fn(async (url: string, init: RequestInit = {}) => {
    const method = init.method ?? 'GET';
    calls.push({ method, url, init, body: typeof init.body === 'string' ? JSON.parse(init.body) : undefined });
    const key = `${method} ${url.split('?')[0]}`;
    const route = routes[key];
    const r: FakeResponse = typeof route === 'function' ? route(url, init) : (route ?? { status: 404, body: { code: 'NOT_FOUND', message: key } });
    const status = r.status ?? 200;
    return { ok: status >= 200 && status < 300, status, text: async () => (r.body === undefined ? '' : JSON.stringify(r.body)) } as Response;
  });
  vi.stubGlobal('fetch', fn);
  return { fn, calls };
}
