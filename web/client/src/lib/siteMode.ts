/**
 * 站点形态（forecast S18，P1）：同一份客户端构建，在对外预热域名（例如 www.quickcome.online）上只渲染等候页兼公开概率看板。
 * 判断只看服务器配置：index.html 的 `<meta name="fc-site-mode">`，部署时只有预热域名的 Nginx server 块把占位符换成 `prelaunch`
 * （deploy/nginx/site.conf.template）；其他域名、本地开发、原型都不换，就是平常的客户端。**不按域名字符串判断**。
 * 测试环境标识另外由后端控制（预热域名的健康检查不返回 env，lib/bannerSpace.ts 的做法不变）。
 */
export type SiteMode = 'app' | 'prelaunch';

const PLACEHOLDER = '__FC_SITE_MODE__';

export function resolveSiteMode(metaValue: string | null | undefined): SiteMode {
  const v = (metaValue || '').trim();
  return v !== PLACEHOLDER && v === 'prelaunch' ? 'prelaunch' : 'app';
}

let cached: SiteMode | undefined;
export function siteMode(): SiteMode {
  if (cached === undefined) {
    const meta = typeof document !== 'undefined' ? document.querySelector('meta[name="fc-site-mode"]')?.getAttribute('content') : null;
    cached = resolveSiteMode(meta);
  }
  return cached;
}

export function isPrelaunch(): boolean {
  return siteMode() === 'prelaunch';
}

/** 测试用：清掉缓存 */
export function resetSiteMode(): void {
  cached = undefined;
}
