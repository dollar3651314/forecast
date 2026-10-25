import type { Lang } from '../api/types';

/**
 * 从地址栏参数里读语言（官网跳转时带 `?lang=`，v2 需求 4.4）。
 * 接受 `en`、`zh`、`zh-CN`、`zh-TW`（不分大小写）；没有参数或值不认识时返回 null（照旧用记住的语言）。
 */
export function langFromSearch(search: string): Lang | null {
  const v = new URLSearchParams(search).get('lang');
  if (!v) return null;
  const s = v.trim().toLowerCase();
  if (s === 'en') return 'en';
  if (s === 'zh-tw') return 'zh-TW';
  if (s === 'zh' || s === 'zh-cn') return 'zh-CN';
  return null;
}

/** 繁体：zh-TW、zh-HK、zh-MO、zh-Hant*（架构方案第 9.2 节） */
function isTraditional(s: string): boolean {
  return /^zh[-_](tw|hk|mo|hant)/.test(s);
}

/**
 * 浏览器语言（体验优化 1021 UX-1，Amos 2026-10-21："客户端和官网都默认跟随浏览器的语言，如果查不到浏览器语言再用英文"）：
 * 看浏览器的首选语言（`navigator.languages` 的第一个，没有时 `navigator.language`）。
 * zh-TW、zh-HK、zh-MO、zh-Hant* 用繁体，其他 zh 开头的用简体；其他语言、或者取不到时用英文（RQ-18、C82）。
 * 和官网的 `web/site/src/i18n/lang.ts` 的 `detectLang` 同一个规则。
 */
export function langFromBrowser(languages: readonly string[] | undefined, language?: string): Lang {
  const first = (languages && languages.length > 0 ? languages[0] : language) || '';
  const s = first.trim().toLowerCase();
  if (isTraditional(s)) return 'zh-TW';
  return s === 'zh' || s.startsWith('zh-') || s.startsWith('zh_') ? 'zh-CN' : 'en';
}

export const LANGS: readonly Lang[] = ['zh-CN', 'zh-TW', 'en'];

/**
 * 打开页面时用哪种语言，按优先级：地址栏 `?lang=`（官网跳转带的，记住）→ 客户自己选过的（`fc.lang`）→ 浏览器语言（不记住）。
 * `remember` 为 true 时调用方把结果写进 `fc.lang`。浏览器语言不记住：客户没选过时，以后换了浏览器语言也跟着变。
 */
export function pickLang(input: { search: string; stored: string | null; languages?: readonly string[]; language?: string }): { lang: Lang; remember: boolean } {
  const fromUrl = langFromSearch(input.search);
  if (fromUrl) return { lang: fromUrl, remember: true };
  if (input.stored && (LANGS as readonly string[]).includes(input.stored)) return { lang: input.stored as Lang, remember: false };
  return { lang: langFromBrowser(input.languages, input.language), remember: false };
}
