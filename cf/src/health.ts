/**
 * 健康检查（forecast S19，K7；C19、C28、C103）：GET /api/health。
 * - status：UP（D1 能读）/ DOWN（D1 读不了，返回 503）；
 * - environment、version（部署时 CI 传进来的提交号）；
 * - checks：board（行情：ok / stale 超过 30 分钟没更新 / down 从没取到过；每个源最近一次的结果）、mail（ok / not_configured）、admin（Access 配了没有）。
 *   "未开通"（not_configured）不是故障，不影响 status（C103）。
 * - staging 另外返回 env: "staging"：页面顶部显示测试标识条（客户端 api/envLabel.ts，和测试服务器同一个做法）；正式版不返回 env。
 * 不返回任何密钥、订阅数据。
 */
import { accessConfigured } from './access';
import { health as boardHealth } from './board';
import type { Env } from './env';
import { json } from './http';
import { waitlistOpen } from './waitlist';

export async function health(env: Env, now = Date.now()): Promise<Response> {
  const environment = env.ENVIRONMENT?.trim() || 'production';
  const base: Record<string, unknown> = { environment, version: env.VERSION?.trim() || 'dev' };
  if (environment !== 'production') base.env = environment;
  try {
    const board = await boardHealth(env, now);
    return json({
      status: 'UP',
      ...base,
      checks: {
        db: 'ok',
        board,
        mail: env.RESEND_API_KEY?.trim() ? 'ok' : 'not_configured',
        admin: accessConfigured(env) ? 'ok' : 'not_configured',
        waitlist: waitlistOpen(env) ? 'open' : 'closed',
      },
    });
  } catch (e) {
    console.log(JSON.stringify({ event: 'health.db_error', error: String((e as Error)?.message || e).slice(0, 200) }));
    return json({ status: 'DOWN', ...base, checks: { db: 'down' } }, 503);
  }
}
