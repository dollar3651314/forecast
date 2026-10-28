/** 带来源的访问、台湾入金教程、健康检查（forecast S19）。 */
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { health } from '../src/health';
import { howTo, localSections } from '../src/howto';
import { visit } from '../src/visit';
import { freshIp, newDb, post, testEnv } from './helpers';

let dispose: () => Promise<void>;
let db: D1Database;
beforeAll(async () => {
  ({ dispose, db } = await newDb());
});
afterAll(async () => {
  await dispose();
});
const NOW = Date.parse('2026-10-28T10:00:00.000Z');

describe('带来源的访问', () => {
  it('按天 + src + cmp 计数；没有来源的不记；同一 IP 每小时超过 30 次的不计（不报错）', async () => {
    const ip = freshIp();
    for (let i = 0; i < 32; i++) expect((await visit(post('/api/attribution/visit', { src: 'x', cmp: 'y', ref: 'r' }, ip), testEnv(db), NOW)).status).toBe(204);
    await visit(post('/api/attribution/visit', { ref: 'only-ref' }, freshIp()), testEnv(db), NOW);
    await visit(post('/api/attribution/visit', { start: 's-tg__c-bot' }, freshIp()), testEnv(db), NOW);
    const rows = await db.prepare('SELECT day, src, cmp, count FROM visit_daily ORDER BY src').all();
    expect(rows.results).toEqual([
      { day: '2026-10-28', src: 'tg', cmp: 'bot', count: 1 },
      { day: '2026-10-28', src: 'x', cmp: 'y', count: 30 },
    ]);
  });
});

describe('台湾入金教程', () => {
  it('只给这种语言、没标 draft 的段落；{brand} 换成品牌；有别的变量的不给', () => {
    const local = {
      'zh-TW': {
        howto: {
          sections: [
            { id: 'deposit-tw', heading: '在台灣買 USDT', items: ['到 {brand} 充值'] },
            { id: 'draft', heading: 'x', items: ['y'], draft: '待定稿' },
            { id: 'vars', heading: 'x', items: ['最少 {minWithdraw}'] },
          ],
        },
      },
    };
    expect(localSections('zh-TW', local)).toEqual([{ id: 'deposit-tw', heading: '在台灣買 USDT', items: ['到 Quick Come 充值'] }]);
    expect(localSections('en', local)).toEqual([]);
  });

  it('接口：后端 public-pages.json 现在标了待定稿，所以不给（和后端一样页面不显示）', async () => {
    const res = howTo(new URL('https://www.example.test/api/public/pages/how-to-play?lang=zh-TW'));
    expect(await res.json()).toEqual({ lang: 'zh-TW', sections: localSections('zh-TW') });
  });
});

describe('健康检查', () => {
  it('UP、环境、版本；未开通的项不影响 status；正式版不返回 env', async () => {
    const res = await health(testEnv(db, { ENVIRONMENT: 'production', VERSION: 'abc123', RESEND_API_KEY: '' }), NOW);
    const b = (await res.json()) as Record<string, unknown> & { checks: Record<string, unknown> };
    expect(res.status).toBe(200);
    expect(b).toMatchObject({ status: 'UP', environment: 'production', version: 'abc123' });
    expect(b.env).toBeUndefined();
    expect(b.checks).toMatchObject({ db: 'ok', mail: 'not_configured', admin: 'not_configured', waitlist: 'open' });
    expect((b.checks.board as { status: string }).status).toBe('down');
    expect(JSON.stringify(b)).not.toContain('ci_only');
  });

  it('staging 返回 env（页面显示测试标识条）', async () => {
    const b = (await (await health(testEnv(db, { ENVIRONMENT: 'staging' }), NOW)).json()) as Record<string, unknown>;
    expect(b.env).toBe('staging');
  });

  it('D1 读不了：503 DOWN', async () => {
    const broken = { prepare: () => { throw new Error('D1 down'); } } as unknown as D1Database;
    const res = await health(testEnv(broken), NOW);
    expect(res.status).toBe(503);
    expect(await res.json()).toMatchObject({ status: 'DOWN', checks: { db: 'down' } });
  });
});
