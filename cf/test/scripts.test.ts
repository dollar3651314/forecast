/** 部署脚本里不连 Cloudflare 的部分（forecast S19）：D1 编号写进配置、解析 wrangler d1 list；P11 检查能发现交易入口。 */
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
// @ts-expect-error 纯 JS 脚本，没有类型声明
import { DB_NAMES, parseList, patchConfig } from '../scripts/ensure-d1.mjs';
// @ts-expect-error 纯 JS 脚本，没有类型声明
import { check } from '../scripts/check-dist.mjs';
import { CF_DIR } from './helpers';

const UUID = '0123abcd-4567-89ab-cdef-0123456789ab';

describe('ensure-d1', () => {
  const toml = readFileSync(path.join(CF_DIR, 'wrangler.toml'), 'utf8');

  it('正式版、测试版各自只改自己那一段的 database_id', () => {
    const p = patchConfig(toml, DB_NAMES.production, UUID);
    expect(p).toContain(`database_name = "forecast-public"\ndatabase_id = "${UUID}"`);
    expect(p).toContain('database_name = "forecast-public-staging"\ndatabase_id = "local-only"');
    const s = patchConfig(toml, DB_NAMES.staging, UUID);
    expect(s).toContain(`database_name = "forecast-public-staging"\ndatabase_id = "${UUID}"`);
    expect(s).toContain('database_name = "forecast-public"\ndatabase_id = "local-only"');
  });

  it('编号格式不对、配置里找不到时停下', () => {
    expect(() => patchConfig(toml, 'forecast-public', 'nope')).toThrow();
    expect(() => patchConfig('name = "x"', 'forecast-public', UUID)).toThrow();
  });

  it('wrangler d1 list --json：前面有提示行也能解析', () => {
    expect(parseList('▲ warning\n[{"uuid":"u","name":"forecast-public"}]')).toEqual([{ uuid: 'u', name: 'forecast-public' }]);
    expect(() => parseList('error')).toThrow();
  });
});

describe('check-dist（P11 底线）', () => {
  function fixture(files: Record<string, string>): string {
    const dir = mkdtempSync(path.join(tmpdir(), 'fc-dist-'));
    for (const [f, c] of Object.entries(files)) {
      mkdirSync(path.dirname(path.join(dir, f)), { recursive: true });
      writeFileSync(path.join(dir, f), c);
    }
    return dir;
  }
  const okFiles = {
    'index.html': '<meta name="fc-site-mode" content="prelaunch" />',
    'assets/a.js': 'fetch("/api/public/waitlist");fetch("/api/public/board");const s="/assets/x.js";',
    'about/index.html': '<script src="/about/assets/b.js"></script>',
    'about/terms.html': 'x',
    'about/privacy.html': 'x',
    'about/risk.html': 'x',
    'about/assets/b.js': '"加入等候名單","加入等候名单","Join the waitlist"',
    'robots.txt': 'x',
    'sitemap.xml': 'x',
    _headers: 'x',
  };

  it('干净的产物通过', () => {
    const d = fixture(okFiles);
    try {
      expect(check(d)).toEqual([]);
    } finally {
      rmSync(d, { recursive: true });
    }
  });

  it('有交易接口、交易页面、测试域名、占位符时不通过', () => {
    const d = fixture({
      ...okFiles,
      'assets/c.js': 'fetch("/api/auth/login");nav("/register");x=`/markets/${id}`;y="https://fc-test.example";',
      'about/index.html': '<meta name="fc-client-url" content="__FC_CLIENT_URL__">',
    });
    try {
      const p = check(d).join('\n');
      expect(p).toContain('/api/auth');
      expect(p).toContain('"/register"');
      expect(p).toContain('`/markets/');
      expect(p).toContain('fc-test');
      expect(p).toContain('__FC_CLIENT_URL__');
      expect(p).toContain('fc-client-url');
    } finally {
      rmSync(d, { recursive: true });
    }
  });
});
