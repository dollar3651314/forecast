#!/usr/bin/env node
/**
 * 构建 Worker 的静态资源目录 cf/dist（forecast S19，P11）：
 *   1. 客户端公开版：web/client `vite build --config vite.public.config.ts` → dist-public/（public.html 改名 index.html，去掉登录后才用的推送脚本 push-sw.js）
 *   2. 官网公开版：web/site 用 SITE_BASE=/about/、VITE_PUBLIC_SITE=true 构建 → cf/dist/about/（页面里的客户端地址占位符去掉，公开版用不到）
 *   3. robots.txt、sitemap.xml（只收录首页和官网，等候页的确认 / 退订和接口不收录）、_headers（安全头、缓存）
 * 前提：web/client、web/site 已经 npm ci（CI 里先装；本机没装时这里先装）。
 * 用法：node scripts/build.mjs [--site-url https://www.quickcome.online]
 * 构建完用 node scripts/check-dist.mjs 检查"产物里没有交易入口"。
 */
import { execFileSync } from 'node:child_process';
import { cpSync, existsSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const cf = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const repo = path.resolve(cf, '..');
const client = path.join(repo, 'web/client');
const site = path.join(repo, 'web/site');
const dist = path.join(cf, 'dist');
const argSite = process.argv.indexOf('--site-url');
const SITE_URL = (argSite > 0 ? process.argv[argSite + 1] : 'https://www.quickcome.online').replace(/\/+$/, '');

function run(cwd, cmd, args, env = {}) {
  console.log(`$ (${path.relative(repo, cwd) || '.'}) ${cmd} ${args.join(' ')}`);
  execFileSync(cmd, args, { cwd, stdio: 'inherit', env: { ...process.env, ...env } });
}

for (const dir of [client, site]) {
  if (!existsSync(path.join(dir, 'node_modules'))) run(dir, 'npm', ['ci', '--no-audit', '--no-fund']);
}

// 1. 客户端公开版
run(client, 'npx', ['tsc', '-b']);
run(client, 'npx', ['vite', 'build', '--config', 'vite.public.config.ts']);
// 2. 官网公开版（真实接口模式：测试标识照样问 /api/health，staging 会显示）
run(site, 'npx', ['tsc', '-b']);
run(site, 'npx', ['vite', 'build', '--outDir', 'dist-public', '--emptyOutDir'], { SITE_BASE: '/about/', VITE_PUBLIC_SITE: 'true', VITE_API_MODE: 'real' });

rmSync(dist, { recursive: true, force: true });
mkdirSync(dist, { recursive: true });
cpSync(path.join(client, 'dist-public'), dist, { recursive: true });
renameSync(path.join(dist, 'public.html'), path.join(dist, 'index.html'));
// 推送的 Service Worker 脚本是登录后才用的，公开版不注册 Service Worker
rmSync(path.join(dist, 'push-sw.js'), { force: true });

cpSync(path.join(site, 'dist-public'), path.join(dist, 'about'), { recursive: true });
const aboutIndex = path.join(dist, 'about/index.html');
writeFileSync(aboutIndex, readFileSync(aboutIndex, 'utf8').replace(/\s*<!--[^>]*客户端地址[\s\S]*?-->\s*/, '\n    ').replace(/\s*<meta name="fc-client-url"[^>]*>/, ''));
// 官网的法律页（/about/terms 等）：静态资源找不到的路径会回到根目录的 index.html（等候页），所以每页放一份官网的 index.html，
// 按 html_handling 的默认规则 /about/terms 直接给 about/terms.html（官网自己按地址显示对应的页面，web/site/src/lib/route.ts）
for (const p of ['terms', 'privacy', 'risk']) cpSync(aboutIndex, path.join(dist, 'about', `${p}.html`));

const langs = ['zh-TW', 'zh-CN', 'en'];
writeFileSync(
  path.join(dist, 'robots.txt'),
  ['User-agent: *', 'Allow: /', 'Disallow: /data/', 'Disallow: /api/', `Sitemap: ${SITE_URL}/sitemap.xml`, ''].join('\n'),
);
const pages = ['/', '/about/', '/about/terms', '/about/privacy', '/about/risk'];
const urls = pages
  .map((p) => {
    const alts = langs.map((l) => `    <xhtml:link rel="alternate" hreflang="${l}" href="${SITE_URL}${p}?lang=${l}"/>`).join('\n');
    return `  <url>\n    <loc>${SITE_URL}${p}</loc>\n${alts}\n  </url>`;
  })
  .join('\n');
writeFileSync(
  path.join(dist, 'sitemap.xml'),
  `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">\n${urls}\n</urlset>\n`,
);
// 静态资源的响应头（Workers 静态资源支持 _headers 文件）；接口的响应头在 src/http.ts
writeFileSync(
  path.join(dist, '_headers'),
  [
    '/*',
    '  X-Content-Type-Options: nosniff',
    '  X-Frame-Options: DENY',
    '  Referrer-Policy: strict-origin-when-cross-origin',
    '  Strict-Transport-Security: max-age=86400',
    '/data/*',
    '  X-Robots-Tag: noindex',
    '/assets/*',
    '  Cache-Control: public, max-age=31536000, immutable',
    '/about/assets/*',
    '  Cache-Control: public, max-age=31536000, immutable',
    '',
  ].join('\n'),
);
console.log(`cf/dist 构建完成（站点 ${SITE_URL}）`);
