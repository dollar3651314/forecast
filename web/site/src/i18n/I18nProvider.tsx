/** 当前语言（默认跟随浏览器，手动切换后记住，见 lang.ts）；切换时同步 <html lang>、标题和描述。 */
import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { I18nContext, translate, type I18n } from './index';
import { initialLang, storeLang, type Lang } from './lang';

export function LangProvider({ children, initial }: { children: ReactNode; initial?: Lang }) {
  const [lang, setLangState] = useState<Lang>(() => initial ?? initialLang());
  const setLang = useCallback((l: Lang) => {
    storeLang(l);
    setLangState(l);
  }, []);
  useEffect(() => {
    document.documentElement.lang = lang;
    document.title = translate(lang, 'meta.title');
    document.querySelector('meta[name="description"]')?.setAttribute('content', translate(lang, 'meta.description'));
  }, [lang]);
  const value = useMemo<I18n>(() => ({ lang, setLang, t: (k, v) => translate(lang, k, v) }), [lang, setLang]);
  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}
