/**
 * 官网地址（S11）：注册页的"服务条款"、"我的"里的规则说明 / 服务条款 / 隐私 / 风险披露链到官网（web/site，三种语言，PM 定稿）。
 * 地址来自 index.html 的 `<meta name="fc-site-url">`：部署时 Nginx 把占位符换成 https://官网域名（deploy/nginx/site.conf.template），
 * 端到端测试站点换成本机官网（e2e/server.mjs）；没换（本地开发）时原型模式用同源的 /site（scripts/build-prototype.sh 的目录），真实模式用同源相对地址。
 * 和官网的 src/config.ts（官网 → 客户端）是同一个做法。
 */
import { IS_MOCK } from '../api/mode';
import type { Lang } from '../api/types';

const PLACEHOLDER = '__FC_SITE_URL__';

export function resolveSiteUrl(metaValue: string | null | undefined, mock: boolean): string {
  const s = (metaValue || '').trim();
  if (s && s !== PLACEHOLDER && /^https?:\/\/[^\s"'<>]+$/.test(s)) return s.replace(/\/+$/, '');
  // S19：Cloudflare 上的公开版把官网放在同一个站点的 /about/，页面里直接写同站路径（public.html）
  // 只认 /段/段 这种写法（不能是 // 开头，那是别的网站）
  if (/^(\/[A-Za-z0-9_-]+)+\/?$/.test(s)) return s.replace(/\/+$/, '');
  return mock ? '/site' : '';
}

let cached: string | undefined;
export function siteUrl(): string {
  if (cached === undefined) {
    const meta = typeof document !== 'undefined' ? document.querySelector('meta[name="fc-site-url"]')?.getAttribute('content') : null;
    cached = resolveSiteUrl(meta, IS_MOCK);
  }
  return cached;
}

export type SitePage = 'home' | 'terms' | 'privacy' | 'risk';

/** 官网某一页的地址，带上当前语言（官网读 ?lang=，web/site/src/i18n/lang.ts） */
export function siteLink(page: SitePage, lang: Lang, base = siteUrl()): string {
  return `${base}/${page === 'home' ? '' : page}?lang=${lang}`;
}
