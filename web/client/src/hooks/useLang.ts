/** 市场页面用到的两个 hook：当前界面语言、"还剩 xx"的文字。 */
import { useTranslation } from 'react-i18next';
import type { Lang } from '../api/types';
import { LANGS } from '../i18n/urlLang';
import { remaining } from '../lib/market';

/** 当前界面语言（随切换重新渲染） */
export function useLang(): Lang {
  const { i18n } = useTranslation();
  return (LANGS as readonly string[]).includes(i18n.language) ? (i18n.language as Lang) : 'en';
}

/** "还剩 6 天 7 小时" / "还剩 42 分" / "还剩 38 秒"；到点后返回 null */
export function useRemainText(target: string, now: number): string | null {
  const { t } = useTranslation();
  const r = remaining(Date.parse(target) - now);
  if (!r) return null;
  return t('mk.closesIn', { t: r.parts.map((p) => t(`mk.unit.${p.unit}`, { n: p.n })).join(' ') });
}
