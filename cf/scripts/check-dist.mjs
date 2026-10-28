#!/usr/bin/env node
/**
 * P11 底线检查（forecast S19）：Cloudflare 上的产物 cf/dist 里**没有任何交易入口**。CI 的 cf job 每次构建后运行，有一项不对就失败。
 * 一、不能有的（在所有 .html、.js 里查）：
 *   - 交易和登录后的接口：/api/auth、/api/me、/api/bets、/api/wallet、/api/account、/api/markets、/api/notifications、/api/ws、/api/withdraw、/api/deposit、/api/kyc、/api/e2e
 *   - 交易页面的路径："/login"、"/register"、"/markets"、"/assets"（页面，不是 /assets/ 下的文件）、"/me"、"/m/…"、"/markets/…"、"/me/…"
 *   - 测试环境的域名（fc-test）、客户端地址（fc-client-url）；页面里没替换的部署占位符（__FC_…__）
 * 二、必须有的：首页 index.html 是公开版（fc-site-mode = prelaunch）；等候页的接口；官网 /about/index.html；robots.txt、sitemap.xml、_headers；
 *   官网公开版有"加入等候名单"三种语言的文字。
 * 用法：node scripts/check-dist.mjs [目录，默认 cf/dist]
 */
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const cf = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const dir = path.resolve(process.argv[2] || path.join(cf, 'dist'));

export const FORBIDDEN = [
  [/\/api\/(auth|me|bets?|wallet|account|markets|notifications|ws|withdraw|deposit|kyc|e2e|admin)\b/, '交易或登录后的接口'],
  [/["'`]\/(login|register|markets|assets|me|wallet|deposit|withdraw)["'`?#]/, '交易页面的路径'],
  [/["'`]\/(markets|me|m)\//, '交易页面的路径'],
  [/fc-test/, '测试环境的域名'],
  [/fc-client-url/, '客户端地址'],
];
/** 只在页面（.html）里查：没替换的部署占位符（JS 里有同名常量用来比较，不算） */
export const FORBIDDEN_HTML = [[/__FC_[A-Z_]+__/, '没替换的部署占位符']];

function walk(d, out = []) {
  for (const f of readdirSync(d)) {
    const p = path.join(d, f);
    if (statSync(p).isDirectory()) walk(p, out);
    else out.push(p);
  }
  return out;
}

export function check(root) {
  const problems = [];
  if (!existsSync(root)) return [`${root} 不存在，先运行 node scripts/build.mjs`];
  const files = walk(root);
  const texts = files.filter((f) => /\.(html|js)$/.test(f));
  for (const f of texts) {
    const s = readFileSync(f, 'utf8');
    for (const [re, what] of f.endsWith('.html') ? [...FORBIDDEN, ...FORBIDDEN_HTML] : FORBIDDEN) {
      const m = s.match(re);
      if (m) problems.push(`${path.relative(root, f)}：有${what}「${m[0]}」`);
    }
  }
  const need = (rel) => {
    if (!existsSync(path.join(root, rel))) problems.push(`缺少 ${rel}`);
  };
  ['index.html', 'about/index.html', 'about/terms.html', 'about/privacy.html', 'about/risk.html', 'robots.txt', 'sitemap.xml', '_headers'].forEach(need);
  if (existsSync(path.join(root, 'push-sw.js'))) problems.push('不应该有 push-sw.js（公开版不注册 Service Worker）');
  if (existsSync(path.join(root, 'index.html'))) {
    const idx = readFileSync(path.join(root, 'index.html'), 'utf8');
    if (!/<meta name="fc-site-mode" content="prelaunch"/.test(idx)) problems.push('index.html 不是公开版（fc-site-mode 应该是 prelaunch）');
  }
  const allJs = texts.filter((f) => f.endsWith('.js')).map((f) => readFileSync(f, 'utf8')).join('\n');
  for (const s of ['/api/public/waitlist', '/api/public/board']) if (!allJs.includes(s)) problems.push(`客户端里没有等候页接口 ${s}`);
  const aboutJs = texts.filter((f) => f.includes(`${path.sep}about${path.sep}`) && f.endsWith('.js')).map((f) => readFileSync(f, 'utf8')).join('\n');
  for (const s of ['加入等候名單', '加入等候名单', 'Join the waitlist']) if (!aboutJs.includes(s)) problems.push(`官网里没有"${s}"`);
  return problems;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const problems = check(dir);
  if (problems.length) {
    console.error(`P11 检查没有通过（${problems.length} 处）：`);
    for (const p of problems) console.error(`- ${p}`);
    process.exit(1);
  }
  console.log(`P11 检查通过：${dir} 里没有交易入口`);
}
