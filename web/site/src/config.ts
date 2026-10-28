/**
 * 官网的固定配置：客户端地址（注册、登录跳过去）。品牌名和联系邮箱在 src/lib/brand.ts。
 * forecast S1 删除了复制源的公开行情（品种短名称、分类）。
 */
import type { Lang } from './i18n/lang';

const PLACEHOLDER = '__FC_CLIENT_URL__';

/**
 * 客户端地址（不带结尾的 /）。优先级：构建变量 VITE_CLIENT_URL → 页面 <meta name="fc-client-url">（部署时由 Nginx 替换）→ 空串（同源相对地址）。
 */
export function resolveClientUrl(buildValue: string | undefined, metaValue: string | null | undefined): string {
  const pick = (v: string | null | undefined) => {
    const s = (v || '').trim();
    return s && s !== PLACEHOLDER && /^https?:\/\/[^\s"'<>]+$/.test(s) ? s.replace(/\/+$/, '') : '';
  };
  return pick(buildValue) || pick(metaValue);
}

let cachedClientUrl: string | undefined;
export function clientUrl(): string {
  if (cachedClientUrl === undefined) {
    const meta = typeof document !== 'undefined' ? document.querySelector('meta[name="fc-client-url"]')?.getAttribute('content') : null;
    cachedClientUrl = resolveClientUrl(import.meta.env.VITE_CLIENT_URL, meta);
  }
  return cachedClientUrl;
}

/** 注册、登录的地址：客户端的 /register、/login，带上当前语言（客户端读 ?lang=，web/client/src/i18n/urlLang.ts） */
export function clientLink(page: 'register' | 'login', lang: Lang, base = clientUrl()): string {
  return `${base}/${page}?lang=${lang}`;
}

/**
 * 公开版（forecast S19，P11）：Cloudflare 上 www 的 /about/ 用这个开关构建（VITE_PUBLIC_SITE=true，cf/scripts/build.mjs）。
 * 交易还没开放：页面上没有"登录"，"注册"换成"加入等候名单"，链到同一个站点首页的等候页（带当前语言）；不指向任何客户端或测试环境的地址。
 * 平常的构建（测试环境的官网）是 false，和以前一样。
 */
export const PUBLIC_SITE = import.meta.env.VITE_PUBLIC_SITE === 'true';

/** 等候页的地址（公开版里官网在 /about/，等候页是同一个站点的首页） */
export function waitlistLink(lang: Lang): string {
  return `/?lang=${lang}`;
}

/** 主按钮的地址：公开版去等候页，平常去客户端注册（PUBLIC_SITE 是构建时的常量，公开版里客户端地址的代码整段不打包） */
export function signupLink(lang: Lang): string {
  return PUBLIC_SITE ? waitlistLink(lang) : clientLink('register', lang);
}

/** 主按钮的文字（翻译键） */
export const SIGNUP_KEY = PUBLIC_SITE ? 'common.waitlist' : 'common.register';
