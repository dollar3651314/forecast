/**
 * 多语言：默认中文，所有界面文字都从翻译文件取（AC-27）。
 * i18n: Chinese by default; every UI string comes from the translation files.
 */
import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import zhCN from './zh-CN.json';
import en from './en.json';
import type { Lang } from '../api/types';

const LANG_KEY = 'fx-admin-lang';

function storedLang(): Lang {
  try {
    const v = globalThis.localStorage?.getItem(LANG_KEY);
    if (v === 'en' || v === 'zh-CN') return v;
  } catch {
    /* 忽略 */
  }
  return 'zh-CN';
}

void i18n.use(initReactI18next).init({
  resources: { 'zh-CN': { translation: zhCN }, en: { translation: en } },
  lng: storedLang(),
  fallbackLng: 'zh-CN',
  interpolation: { escapeValue: false },
  returnNull: false,
});

export function setLanguage(lang: Lang): void {
  void i18n.changeLanguage(lang);
  try {
    globalThis.localStorage?.setItem(LANG_KEY, lang);
  } catch {
    /* 忽略 */
  }
  document.documentElement.lang = lang;
}

export default i18n;
