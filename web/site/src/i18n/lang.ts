/**
 * 官网语言（v2 需求第 10 节 W6；体验优化 1021 UX-1，Amos 2026-10-21："客户端和官网都默认跟随浏览器的语言，如果查不到浏览器语言再用英文"）：
 * 默认跟随浏览器——繁体地区（zh-TW、zh-HK、zh-MO、zh-Hant）用繁体，其他 zh 用简体，其他语言或取不到时用英文（RQ-18）；
 * 访客在语言下拉框里选过后记住（localStorage），以后优先用选的。跳客户端时带 `?lang=`（config.ts 的 clientLink），客户端以它为准。
 * 规则和客户端 web/client/src/i18n/urlLang.ts 的 langFromBrowser 一致。
 */
export type Lang = 'zh-CN' | 'zh-TW' | 'en';

export const LANG_KEY = 'fc.site.lang';

/** 记住的语言优先；没有时看浏览器的首选语言（navigator.languages 的第一个，没有时 navigator.language） */
export function detectLang(stored: string | null | undefined, languages: readonly string[] | undefined, language?: string): Lang {
  if (stored === 'zh-CN' || stored === 'zh-TW' || stored === 'en') return stored;
  const first = (languages && languages.length > 0 ? languages[0] : language) || '';
  const s = first.trim().toLowerCase();
  // 繁体：zh-TW、zh-HK、zh-MO、zh-Hant*（RQ-18，和客户端 urlLang.ts 一致）
  if (/^zh[-_](tw|hk|mo|hant)/.test(s)) return 'zh-TW';
  return s === 'zh' || s.startsWith('zh-') || s.startsWith('zh_') ? 'zh-CN' : 'en';
}

export function readStoredLang(): string | null {
  try {
    return localStorage.getItem(LANG_KEY);
  } catch {
    return null;
  }
}

export function storeLang(l: Lang): void {
  try {
    localStorage.setItem(LANG_KEY, l);
  } catch {
    /* 隐私模式等：不记住也能用 */
  }
}

/** 地址栏 ?lang=（客户端"我的"里的服务条款等链接带上客户端的语言，S11）；不认识的忽略 */
export function langFromSearch(search: string | undefined): Lang | null {
  const v = new URLSearchParams(search ?? '').get('lang');
  return v === 'zh-CN' || v === 'zh-TW' || v === 'en' ? v : null;
}

export function initialLang(): Lang {
  const nav = typeof navigator !== 'undefined' ? navigator : undefined;
  const fromUrl = typeof location !== 'undefined' ? langFromSearch(location.search) : null;
  return fromUrl ?? detectLang(readStoredLang(), nav?.languages, nav?.language);
}
