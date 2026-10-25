/**
 * 多语言（RQ-18）：简体中文、繁體中文、英文。三份翻译文件的键必须完全一致（见 i18n.test.ts）。
 * 繁体由简体转换后人工校对（转换脚本和校对记录见 开发记录/M1-原型.md），改简体时同步改繁体。
 * 打开页面时的语言（体验优化 1021 UX-1，Amos 2026-10-21："客户端和官网都默认跟随浏览器的语言，如果查不到浏览器语言再用英文"，
 * 规则在 urlLang.ts 的 pickLang）：
 * 1. 地址栏 `?lang=zh-CN|en`（官网跳到注册、登录页时带，v2 需求 4.4）：以它为准并记住；
 * 2. 客户自己在语言下拉框里选过的（`fc.lang`）；
 * 3. 浏览器语言：zh-TW、zh-HK、zh-MO、zh-Hant 用繁体，其他 zh 开头的用简体，其他语言或取不到时用英文（不记住）。
 */
import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import zhCN from './zh-CN.json';
import zhTW from './zh-TW.json';
import en from './en.json';
import type { Lang } from '../api/types';
import { setPushLanguage } from '../api/notifications';
import { LANGS, pickLang } from './urlLang';

const LANG_KEY = 'fc.lang';

function initialLang(): Lang {
  let stored: string | null = null;
  try {
    stored = localStorage.getItem(LANG_KEY);
  } catch {
    /* 忽略 */
  }
  const nav = typeof navigator !== 'undefined' ? navigator : undefined;
  const { lang, remember } = pickLang({ search: typeof location !== 'undefined' ? location.search : '', stored, languages: nav?.languages, language: nav?.language });
  if (remember) {
    try {
      localStorage.setItem(LANG_KEY, lang);
    } catch {
      /* 忽略 */
    }
  }
  return lang;
}

const lng = initialLang();

void i18n.use(initReactI18next).init({
  resources: { 'zh-CN': { translation: zhCN }, 'zh-TW': { translation: zhTW }, en: { translation: en } },
  lng,
  fallbackLng: 'zh-CN',
  interpolation: { escapeValue: false }, // React 自己会转义
  returnNull: false,
});

function applyLang(l: Lang): void {
  if (typeof document !== 'undefined') document.documentElement.lang = l;
  setPushLanguage(l);
}
applyLang(lng);

export function currentLang(): Lang {
  return (LANGS as readonly string[]).includes(i18n.language) ? (i18n.language as Lang) : 'en';
}

/** 客户自己选语言（语言下拉框）：记住，以后优先用它 */
export async function setLanguage(l: Lang): Promise<void> {
  await i18n.changeLanguage(l);
  try {
    localStorage.setItem(LANG_KEY, l);
  } catch {
    /* 忽略 */
  }
  applyLang(l);
}

export default i18n;
