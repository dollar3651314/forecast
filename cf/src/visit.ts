/**
 * 带来源参数的访问（forecast S19，P14）：页面已有的 POST /api/attribution/visit（S13 客户端 lib/attribution.ts，每次页面加载同一组参数只报一次）。
 * 只按"天 + src + cmp"计数（visit_daily），用于看"访问 → 订阅"的转化；不记 IP、设备号。按 IP 每小时 30 次，超过的不计也不报错（统计用，失败不影响页面）。
 */
import { sourceOf } from './attribution';
import type { Env } from './env';
import { clientIp, readJson } from './http';
import { hit } from './ratelimit';
import { iso, sha256 } from './util';

export async function visit(req: Request, env: Env, now = Date.now()): Promise<Response> {
  const r = await readJson(req);
  if (!r.ok) return r.res;
  const a = sourceOf(r.body.src, r.body.cmp, r.body.ref, r.body.start);
  if (a.src || a.cmp) {
    const lim = await hit(env.DB, 'visit', await sha256(clientIp(req)), 30, 60 * 60 * 1000, now);
    if (lim.ok) {
      await env.DB.prepare(
        'INSERT INTO visit_daily (day, src, cmp, count) VALUES (?1, ?2, ?3, 1) ON CONFLICT (day, src, cmp) DO UPDATE SET count = count + 1',
      )
        .bind(iso(now).slice(0, 10), a.src ?? '', a.cmp ?? '')
        .run();
    }
  }
  return new Response(null, { status: 204 });
}
