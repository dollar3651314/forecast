/**
 * 官网只有 4 个页面（需求第 5 节站点地图）：首页 /、服务条款 /terms、隐私政策 /privacy、风险披露 /risk。
 * 不引入路由库：按地址判断页面，站内链接用 history.pushState（Nginx 把这几个路径都回到 index.html）。
 * base 是构建时的 import.meta.env.BASE_URL：部署时是 /，原型站是 /site/。
 */
export type Page = 'home' | 'terms' | 'privacy' | 'risk' | 'notFound';
export const LEGAL_PAGES = ['terms', 'privacy', 'risk'] as const;
export type LegalPage = (typeof LEGAL_PAGES)[number];

export function pageFromPath(pathname: string, base: string): Page {
  const b = base.endsWith('/') ? base : `${base}/`;
  let rest = pathname.startsWith(b) ? pathname.slice(b.length) : pathname === b.slice(0, -1) ? '' : null;
  if (rest === null) return 'notFound';
  rest = rest.replace(/\/+$/, '').replace(/\/index\.html$|^index\.html$/, '');
  if (rest === '') return 'home';
  return (LEGAL_PAGES as readonly string[]).includes(rest) ? (rest as LegalPage) : 'notFound';
}

export function pathFor(page: Exclude<Page, 'notFound'>, base: string): string {
  const b = base.endsWith('/') ? base : `${base}/`;
  return page === 'home' ? b : `${b}${page}`;
}
