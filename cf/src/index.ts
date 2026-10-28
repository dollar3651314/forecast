/**
 * forecast 的公开部分（S19；市场需求单 v3 P11～P17）：Cloudflare Workers 静态资源 + D1，一个 Worker。
 * - 只有 /api/* 先进这里（wrangler.toml 的 run_worker_first），其余路径由静态资源直接给（等候页、官网 /about/），找不到的回 index.html。
 * - 交易完全不在这里：没有注册、登录、充值、下注、提现的接口，/api/ 下面没列出来的一律 404（P11 底线）。
 * - 定时任务（Cron，每 5 分钟）：取 BTC 行情、删过期的限次计数。
 */
import { admin } from './admin';
import { board, refresh } from './board';
import type { Env } from './env';
import { health } from './health';
import { howTo } from './howto';
import { error, notFound } from './http';
import { cleanup } from './ratelimit';
import { visit } from './visit';
import { confirm, subscribe, unsubscribe } from './waitlist';

async function route(req: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  const url = new URL(req.url);
  const p = url.pathname;
  const m = req.method;
  if (p.startsWith('/api/admin')) return admin(req, env, p);
  if (m === 'GET' || m === 'HEAD') {
    if (p === '/api/health') return health(env);
    if (p === '/api/public/board') return board(env);
    if (p === '/api/public/pages/how-to-play') return howTo(url);
  }
  if (m === 'POST') {
    if (p === '/api/public/waitlist') return subscribe(req, env, ctx);
    if (p === '/api/public/waitlist/confirm') return confirm(req, env);
    if (p === '/api/public/waitlist/unsubscribe') return unsubscribe(req, env);
    if (p === '/api/attribution/visit') return visit(req, env);
  }
  return notFound();
}

export default {
  async fetch(req: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(req.url);
    if (!url.pathname.startsWith('/api/')) return env.ASSETS.fetch(req);
    try {
      return await route(req, env, ctx);
    } catch (e) {
      console.log(JSON.stringify({ event: 'api.error', path: url.pathname, error: String((e as Error)?.stack || e).slice(0, 500) }));
      return error(500, 'INTERNAL_ERROR', '服务器出错，请稍后再试');
    }
  },

  async scheduled(_event: ScheduledController, env: Env, ctx: ExecutionContext): Promise<void> {
    const now = Date.now();
    ctx.waitUntil(
      (async () => {
        const used = await refresh(env, now);
        console.log(JSON.stringify({ event: 'board.refresh', source: used }));
        await cleanup(env.DB, now);
      })(),
    );
  },
} satisfies ExportedHandler<Env>;

