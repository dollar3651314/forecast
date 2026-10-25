/**
 * 语言下拉框（体验优化 1021 第 1 条）：按钮上显示**当前**语言，点开是传统下拉列表，当前语言打勾，点另一项切换。
 * 原来的 LangSwitch 按钮上写的是"要切换到的语言"，Amos 试用时误以为那是当前语言。
 * 默认跟随浏览器语言（中文浏览器用中文，其他语言或取不到时用英文，i18n/index.ts，UX-1）；在这里选过以后记住、优先用选的。
 */
import { useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { Lang } from '../api/types';
import { currentLang, setLanguage } from '../i18n';
import { Icon } from './Icon';

const LANG_OPTIONS: Array<{ value: Lang; label: string }> = [
  { value: 'zh-CN', label: '简体中文' },
  { value: 'zh-TW', label: '繁體中文' },
  { value: 'en', label: 'English' },
];

export function LangSelect({ compact }: { compact?: boolean }) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const listId = useId();
  const current: Lang = currentLang();
  const label = LANG_OPTIONS.find((o) => o.value === current)?.label ?? '简体中文';

  useEffect(() => {
    if (!open) return;
    const close = (e: Event) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    const esc = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false);
    document.addEventListener('pointerdown', close);
    document.addEventListener('keydown', esc);
    return () => {
      document.removeEventListener('pointerdown', close);
      document.removeEventListener('keydown', esc);
    };
  }, [open]);

  const pick = (l: Lang) => {
    setOpen(false);
    if (l !== current) void setLanguage(l);
  };

  return (
    <div className={`lang-select${compact ? ' compact' : ''}`} ref={ref}>
      <button
        type="button"
        className="lang-select-btn"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={open ? listId : undefined}
        aria-label={`${t('me.language')}：${label}`}
        onClick={() => setOpen((o) => !o)}
      >
        <Icon name="globe" size={16} />
        <span>{label}</span>
        <Icon name="chevronDown" size={12} strokeWidth={2.4} />
      </button>
      {open && (
        <ul className="lang-select-list" role="listbox" id={listId} aria-label={t('me.language')}>
          {LANG_OPTIONS.map((o) => {
            const on = o.value === current;
            return (
              <li key={o.value} role="option" aria-selected={on} lang={o.value}>
                <button type="button" className={on ? 'on' : ''} onClick={() => pick(o.value)}>
                  <span>{o.label}</span>
                  {on ? (
                    <span className="lang-current">
                      <Icon name="check" size={14} strokeWidth={2.6} />
                      {t('lang.current')}
                    </span>
                  ) : null}
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
