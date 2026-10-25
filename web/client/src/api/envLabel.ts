/**
 * 测试环境标识（S20，部署方案第 7 节）。
 * - real 模式：启动时调一次 `GET /api/health`，返回里有非空的 `env`（后端 `FC_ENV_LABEL`，测试环境是 `test`）就显示"测试环境"标识条；
 *   生产不设 `FC_ENV_LABEL`，后端不返回 `env`，不显示。
 * - mock 模式（原型）：不调用，保持原来的原型横幅。
 * - 健康检查失败（503、断网、超时、返回的不是 JSON）一律当成没有标识：不显示，也不报错。
 * 只调用一次：结果缓存在模块里，客户端的登录页和登录后的布局共用。
 */
import { IS_MOCK } from './mode';

const TIMEOUT_MS = 10_000;

/** 取一次健康检查里的 env；失败或没有时返回空串。不走 http.ts：健康检查不需要登录，也不能触发"401 回到登录页" */
export async function fetchEnvLabel(): Promise<string> {
  const ctrl = typeof AbortController !== 'undefined' ? new AbortController() : undefined;
  const timer = ctrl ? setTimeout(() => ctrl.abort(), TIMEOUT_MS) : undefined;
  try {
    const res = await fetch('/api/health', { headers: { Accept: 'application/json' }, credentials: 'same-origin', signal: ctrl?.signal });
    if (!res.ok) return '';
    const body = JSON.parse(await res.text()) as { env?: unknown };
    return typeof body?.env === 'string' ? body.env.trim() : '';
  } catch {
    return '';
  } finally {
    if (timer) clearTimeout(timer);
  }
}

let cached: Promise<string> | undefined;

/** 当前环境标识（启动后只请求一次）；mock 模式固定为空串，不发请求 */
export function loadEnvLabel(): Promise<string> {
  if (IS_MOCK) return Promise.resolve('');
  cached ??= fetchEnvLabel();
  return cached;
}

/** 测试用：清掉缓存 */
export function resetEnvLabel(): void {
  cached = undefined;
}
