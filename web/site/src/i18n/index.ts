/**
 * 官网的中英文（不引入 i18next：只有静态文案和 {name} 这样的占位符）。Provider 在 I18nProvider.tsx。
 * 两份翻译文件的键必须完全一致，英文里不能有中文（i18n.test.ts 检查，AC2-05）。PM 可以改文案，不改键。
 */
import { createContext, useContext } from 'react';
import zhCN from './zh-CN.json';
import en from './en.json';
import type { Lang } from './lang';
import { BRAND_NAME } from '../lib/brand';

export type { Lang } from './lang';

type Dict = Record<string, unknown>;
const DICTS: Record<Lang, Dict> = { 'zh-CN': zhCN, en };

/** 取翻译；键不存在时返回键本身（测试会发现）。{brand} 自动换成品牌名（src/lib/brand.ts） */
export function translate(lang: Lang, key: string, vars?: Record<string, string>): string {
  const v = key.split('.').reduce<unknown>((o, k) => (o && typeof o === 'object' ? (o as Dict)[k] : undefined), DICTS[lang]);
  if (typeof v !== 'string') return key;
  const all: Record<string, string> = { brand: BRAND_NAME, ...vars };
  return v.replace(/\{(\w+)\}/g, (m, k: string) => all[k] ?? m);
}

export interface I18n {
  lang: Lang;
  setLang: (l: Lang) => void;
  t: (key: string, vars?: Record<string, string>) => string;
}

export const I18nContext = createContext<I18n | null>(null);

export function useI18n(): I18n {
  const v = useContext(I18nContext);
  if (!v) throw new Error('useI18n 必须在 LangProvider 里使用');
  return v;
}
