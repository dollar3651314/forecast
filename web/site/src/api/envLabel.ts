/**
 * 测试环境标识（AC2-10，和 web/client/src/api/envLabel.ts 同一做法）：real 模式启动时调一次 `GET /api/health`，
 * 返回非空 `env`（后端 FC_ENV_LABEL，测试环境是 test）就在页面顶部显示"测试环境"；生产不设，不显示。
 * mock 模式不调用。失败一律当成没有标识，不报错。
 */
import { IS_MOCK } from './mode';

export async function fetchEnvLabel(): Promise<string> {
  if (IS_MOCK) return '';
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 10_000);
  try {
    const res = await fetch('/api/health', { headers: { Accept: 'application/json' }, credentials: 'omit', signal: ctrl.signal });
    if (!res.ok) return '';
    const body = JSON.parse(await res.text()) as { env?: unknown };
    return typeof body?.env === 'string' ? body.env.trim() : '';
  } catch {
    return '';
  } finally {
    clearTimeout(timer);
  }
}
